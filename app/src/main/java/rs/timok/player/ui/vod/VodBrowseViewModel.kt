package rs.timok.player.ui.vod

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rs.timok.player.data.db.WatchProgressEntity
import rs.timok.player.data.db.WatchlistEntity
import rs.timok.player.data.repo.ChannelRepository
import rs.timok.player.data.repo.PlaylistRepository
import rs.timok.player.data.repo.VodRepository
import rs.timok.player.domain.SourceCategory
import rs.timok.player.domain.VodItem
import rs.timok.player.domain.VodKind
import rs.timok.player.domain.VodSort
import rs.timok.player.data.settings.SettingsStore
import rs.timok.player.ui.components.userMessage
import javax.inject.Inject

/** A poster in the grid; [progress] is set for "continue watching" entries. */
data class GridEntry(val item: VodItem, val progress: Float? = null, val subtitle: String? = null)

sealed interface ItemsState {
    data object Loading : ItemsState
    data class Error(val message: String) : ItemsState
    data class Ready(val entries: List<GridEntry>) : ItemsState
}

const val CONTINUE_KEY = "__continue"
const val MY_LIST_KEY = "__my_list"

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VodBrowseViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
    private val vod: VodRepository,
    playlists: PlaylistRepository,
    private val channels: ChannelRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    val kind: VodKind = VodKind.valueOf(savedState.get<String>("kind") ?: VodKind.MOVIE.name)

    private val allCategories = MutableStateFlow<List<SourceCategory>?>(null)

    private val hidden = playlists.activePlaylist.map { it?.id }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptySet()) else channels.hiddenKeys(id) }

    /** Server categories minus the ones hidden in settings. */
    val categories: StateFlow<List<SourceCategory>?> = combine(allCategories, hidden) { all, h ->
        all?.filter { ChannelRepository.vodKey(kind, it.id) !in h }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _categoriesError = MutableStateFlow<String?>(null)
    val categoriesError: StateFlow<String?> = _categoriesError

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected

    private val networkItems = MutableStateFlow<ItemsState>(ItemsState.Loading)

    val continueEntries: StateFlow<List<GridEntry>> = playlists.activePlaylist
        .map { it?.id }
        .flatMapLatest { vod.continueWatching(kind, it) }
        .map { rows -> rows.map(::toEntry) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** "My List": titles saved to watch later, newest first. */
    val myListEntries: StateFlow<List<GridEntry>> = playlists.activePlaylist
        .map { it?.id }
        .flatMapLatest { vod.watchlist(kind, it) }
        .map { rows -> rows.map(::toEntry) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val sort: StateFlow<VodSort> = settings.vodSort.stateIn(viewModelScope, SharingStarted.Eagerly, VodSort.DEFAULT)

    /** "Continue watching" and "My List" keep their own order (most recent first). */
    fun setSort(order: VodSort) {
        viewModelScope.launch { settings.setVodSort(order) }
    }

    val items: StateFlow<ItemsState> = combine(_selected, networkItems, continueEntries, myListEntries, sort) { sel, net, cont, mine, order ->
        when {
            sel == CONTINUE_KEY -> ItemsState.Ready(cont)
            sel == MY_LIST_KEY -> ItemsState.Ready(mine)
            net is ItemsState.Ready -> ItemsState.Ready(sorted(net.entries, order))
            else -> net
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ItemsState.Loading)

    private fun sorted(entries: List<GridEntry>, order: VodSort): List<GridEntry> = when (order) {
        VodSort.DEFAULT -> entries
        // Items without the value go last; sortedWith is stable, so ties keep the server order.
        VodSort.ADDED -> entries.sortedWith(compareByDescending(nullsFirst()) { it.item.added })
        VodSort.RATING -> entries.sortedWith(compareByDescending(nullsFirst()) { it.item.ratingValue })
        VodSort.YEAR -> entries.sortedWith(compareByDescending(nullsFirst()) { it.item.sortYear() })
        VodSort.NAME -> entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.item.name.withoutPrefix() })
    }

    /** Poster that gets focus when coming back from details. */
    var focusedItemId: Long? = null

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                allCategories.value = vod.categories(kind)
                val cats = categories.filterNotNull().first()
                // Start on "continue watching" when there is something to continue.
                delay(50)
                val start = if (continueEntries.value.isNotEmpty()) CONTINUE_KEY else cats.firstOrNull()?.id
                start?.let { select(it, immediate = true) }
            } catch (e: Exception) {
                _categoriesError.value = e.userMessage(context)
            }
        }
    }

    /** Selection follows focus; a short delay avoids loading every category the focus passes. */
    fun select(categoryId: String, immediate: Boolean = false) {
        if (_selected.value == categoryId) return
        _selected.value = categoryId
        loadJob?.cancel()
        if (categoryId == CONTINUE_KEY || categoryId == MY_LIST_KEY) return
        networkItems.value = ItemsState.Loading
        loadJob = viewModelScope.launch {
            if (!immediate) delay(SELECT_DELAY_MS)
            networkItems.value = try {
                ItemsState.Ready(vod.items(kind, categoryId).map { GridEntry(it) })
            } catch (e: Exception) {
                ItemsState.Error(e.userMessage(context))
            }
        }
    }

    private fun toEntry(w: WatchlistEntity): GridEntry = GridEntry(
        VodItem(
            kind = VodKind.valueOf(w.kind),
            id = w.itemId,
            name = w.title,
            poster = w.poster,
            rating = w.rating,
            year = w.year,
            extension = w.extension,
        )
    )

    private fun toEntry(p: WatchProgressEntity): GridEntry {
        val isEpisode = p.kind == "EPISODE"
        val item = VodItem(
            kind = if (isEpisode) VodKind.SERIES else VodKind.MOVIE,
            id = if (isEpisode) p.seriesId ?: p.itemId else p.itemId,
            name = p.title,
            poster = p.poster,
            rating = null,
            year = null,
            extension = p.extension,
        )
        val subtitle = if (isEpisode && p.season != null && p.episode != null) "S${p.season} E${p.episode}" else null
        val progress = if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else null
        return GridEntry(item, progress, subtitle)
    }

    private companion object {
        /** Provider tags such as "EX - ", "EN| " or "[EXYU] " would otherwise decide the A–Z order. */
        val NAME_PREFIX = Regex("""^\s*(\[[^\]]{1,12}]|[A-Z0-9]{1,6}\s*(\||:|\s-))\s*""")
        fun String.withoutPrefix() = replace(NAME_PREFIX, "")

        /**
         * The year shown in the title ("Name - 2019") wins over the year field, which many panels leave
         * empty or fill inconsistently; the viewer sees the title, so the order must match it.
         */
        val YEAR_IN_NAME = Regex("""\b(19[0-9]{2}|20[0-9]{2})\b""")
        fun VodItem.sortYear(): Int? =
            YEAR_IN_NAME.findAll(name).lastOrNull()?.value?.toIntOrNull() ?: year?.toIntOrNull()
        const val SELECT_DELAY_MS = 300L
    }
}
