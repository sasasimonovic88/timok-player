package rs.timok.player.ui.search

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import rs.timok.player.data.db.ChannelRow
import rs.timok.player.data.repo.ChannelRepository
import rs.timok.player.data.repo.GroupKey
import rs.timok.player.data.repo.PlaylistRepository
import rs.timok.player.data.repo.VodRepository
import rs.timok.player.domain.VodItem
import rs.timok.player.domain.VodKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rs.timok.player.data.settings.SettingsStore
import rs.timok.player.player.PlaybackController
import java.text.Normalizer
import javax.inject.Inject

/** One search: channels first, then movies and series (which load in the background on the first query). */
data class SearchResults(
    val channels: List<ChannelRow>,
    val movies: List<VodItem>,
    val series: List<VodItem>,
    val vodLoading: Boolean,
    val vodFailed: Boolean,
) {
    val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
    val count get() = channels.size + movies.size + series.size
}

private sealed interface VodState {
    data object Idle : VodState
    data object Loading : VodState
    data object Failed : VodState
    /** Items paired with their normalized names, computed once per load. */
    data class Ready(val movies: List<Pair<VodItem, String>>, val series: List<Pair<VodItem, String>>) : VodState
}

enum class SearchTab { CHANNELS, MOVIES, SERIES }

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    playlists: PlaylistRepository,
    channels: ChannelRepository,
    private val vod: VodRepository,
    private val settings: SettingsStore,
    private val playback: PlaybackController,
    private val parental: rs.timok.player.data.parental.ParentalControl,
) : ViewModel() {

    private val normalized = ConcurrentHashMap<String, String>()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /** All visible channels (hidden categories excluded), from the shared in-memory index. */
    private val allChannels = playlists.activePlaylist
        .map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else channels.channels(id, GroupKey.All) }
        // The index re-emits every minute (guide); names only change on a channel refresh, so cache them.
        .map { rows -> rows.map { it to normalized.getOrPut(it.name) { normalize(it.name) } } }
        .flowOn(Dispatchers.Default)

    private val vodState = MutableStateFlow<VodState>(VodState.Idle)

    /** Movie and series category keys hidden in settings or locked; their items stay out of the results too. */
    private val hidden = playlists.activePlaylist.map { it?.id }.distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) flowOf(emptySet())
            else combine(channels.hiddenKeys(id), parental.activeLockedKeys(id)) { h, locked -> h + locked }
        }

    /** Null while the query is empty (nothing searched yet). */
    val results: StateFlow<SearchResults?> =
        combine(allChannels, vodState, hidden, _query.debounce(150)) { rows, vodLists, h, q -> search(rows, vodLists, h, q) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Fetched while the viewer is still typing on the remote; the repository keeps them for the session.
        viewModelScope.launch { loadVod() }
    }

    private suspend fun loadVod() {
        vodState.value = VodState.Loading
        vodState.value = try {
            val movies = vod.allItems(VodKind.MOVIE)
            val series = vod.allItems(VodKind.SERIES)
            // Adult categories can still be opened under Movies, but never pop up in search results.
            val adult = VodKind.entries.associateWith { kind ->
                vod.categories(kind).filter { rs.timok.player.data.parental.ParentalControl.ADULT.containsMatchIn(it.name) }.map { it.id }.toSet()
            }
            withContext(Dispatchers.Default) {
                fun prepare(items: List<VodItem>, kind: VodKind) = items
                    .filter { it.categoryId == null || it.categoryId !in adult.getValue(kind) }
                    .map { it to normalize(it.name) }
                VodState.Ready(prepare(movies, VodKind.MOVIE), prepare(series, VodKind.SERIES))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VodState.Failed
        }
    }

    val playingId: StateFlow<Long?> = playback.currentStreamId

    private val _tab = MutableStateFlow(SearchTab.CHANNELS)
    /** Result tab; kept here so it survives the trip to the details screen. */
    val tab: StateFlow<SearchTab> = _tab

    fun selectTab(value: SearchTab) {
        _tab.value = value
    }

    /** List key of the result opened last; focus returns to it when the viewer comes back. */
    var openedKey: Any? = null

    fun setQuery(value: String) {
        _query.value = value
    }

    /** Same as the channel list: the first OK previews in the background, OK on the playing channel opens it. */
    suspend fun onChannelClick(row: ChannelRow): Boolean {
        if (!settings.backgroundPlayback.first()) return true
        if (playback.currentStreamId.value == row.streamId) return true
        playback.play(row.streamId, GroupKey.All)
        return false
    }

    private fun search(
        rows: List<Pair<ChannelRow, String>>,
        vodLists: VodState,
        hidden: Set<String>,
        query: String,
    ): SearchResults? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val ready = vodLists as? VodState.Ready
        val words = normalize(q).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList(), false, false)
        fun vodMatches(items: List<Pair<VodItem, String>>?, kind: VodKind) = items.orEmpty()
            .filter { (item, name) ->
                words.all { it in name } &&
                    item.categoryId?.let { ChannelRepository.vodKey(kind, it) in hidden } != true
            }
            .sortedBy { (_, name) -> rank(name, words.first()) }
            .take(MAX_VOD_RESULTS)
            .map { it.first }
        return SearchResults(
            channels = searchChannels(rows, q, words),
            movies = vodMatches(ready?.movies, VodKind.MOVIE),
            series = vodMatches(ready?.series, VodKind.SERIES),
            vodLoading = vodLists is VodState.Loading || vodLists is VodState.Idle,
            vodFailed = vodLists is VodState.Failed,
        )
    }

    private fun searchChannels(rows: List<Pair<ChannelRow, String>>, q: String, words: List<String>): List<ChannelRow> {
        // A plain number finds channels by their number (exact first), like typing it in the player.
        if (q.all { it.isDigit() }) {
            val (exact, prefix) = rows.map { it.first }.filter { it.number.toString().startsWith(q) }
                .partition { it.number.toString() == q }
            val byName = rows.filter { (_, name) -> q in name }.map { it.first }
            return (exact + prefix + byName).distinctBy { it.streamId }
        }
        // Every word must appear somewhere in the name; names starting with the query come first.
        val first = words.firstOrNull() ?: return emptyList()
        return rows.filter { (_, name) -> words.all { it in name } }
            .sortedBy { (_, name) -> rank(name, first) }
            .map { it.first }
    }

    private companion object {
        /** Enough to find a title; a short query would otherwise list thousands of posters. */
        const val MAX_VOD_RESULTS = 60
        private val MARKS = Regex("\\p{Mn}+")

        /** Name starting with the query first, then a word starting with it, then a match inside a word. */
        fun rank(name: String, first: String): Int = when {
            name.startsWith(first) -> 0
            " $first" in name -> 1
            else -> 2
        }

        /** Lowercase, without diacritics (č→c, đ→d) and punctuation, so "rtl hr" finds "RTL HR". */
        fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase().replace('đ', 'd'), Normalizer.Form.NFD)
                .replace(MARKS, "")
                .map { if (it.isLetterOrDigit()) it else ' ' }
                .joinToString("")
                .replace(Regex(" +"), " ")
                .trim()
    }
}
