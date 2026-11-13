package rs.timok.player.ui.vod

import rs.timok.player.player.ExternalPlayer
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rs.timok.player.data.db.WatchProgressEntity
import rs.timok.player.data.repo.PlaylistRepository
import rs.timok.player.data.repo.VodPlayRequest
import rs.timok.player.data.repo.VodRepository
import rs.timok.player.domain.Episode
import rs.timok.player.domain.VodDetails
import rs.timok.player.domain.VodKind
import rs.timok.player.ui.components.userMessage
import javax.inject.Inject
import javax.inject.Named

sealed interface DetailsState {
    data object Loading : DetailsState
    data class Error(val message: String) : DetailsState
    data class Ready(val details: VodDetails) : DetailsState
}

/** What the main button plays: a request plus the position to start from (0 = beginning). */
data class PlayTarget(val request: VodPlayRequest, val startMs: Long, val episode: Episode? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VodDetailsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
    private val vod: VodRepository,
    playlists: PlaylistRepository,
    @Named("userAgent") private val userAgent: String,
) : ViewModel() {

    val kind: VodKind = VodKind.valueOf(savedState.get<String>("kind") ?: VodKind.MOVIE.name)
    private val id: Long = savedState.get<Long>("id") ?: 0

    private val _state = MutableStateFlow<DetailsState>(DetailsState.Loading)
    val state: StateFlow<DetailsState> = _state

    private val playlistId = playlists.activePlaylist.map { it?.id }

    val movieProgress: StateFlow<WatchProgressEntity?> = playlistId
        .flatMapLatest { pid -> if (pid == null || kind != VodKind.MOVIE) flowOf(null) else vod.movieProgress(pid, id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Progress per episode id. */
    val episodeProgress: StateFlow<Map<Long, WatchProgressEntity>> = playlistId
        .flatMapLatest { pid -> if (pid == null || kind != VodKind.SERIES) flowOf(emptyList()) else vod.episodeProgress(pid, id) }
        .map { list -> list.associateBy { it.itemId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Whether this title is on "My List". */
    val onWatchlist: StateFlow<Boolean> = playlistId
        .flatMapLatest { pid -> if (pid == null) flowOf(false) else vod.isOnWatchlist(pid, kind, id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleWatchlist(details: VodDetails) {
        viewModelScope.launch { vod.toggleWatchlist(details) }
    }

    private val _season = MutableStateFlow<Int?>(null)
    val season: StateFlow<Int?> = _season

    init {
        viewModelScope.launch {
            _state.value = try {
                val details = vod.details(kind, id)
                _season.value = seriesResume(details)?.episode?.season ?: details.seasons.firstOrNull()?.number
                DetailsState.Ready(details)
            } catch (e: Exception) {
                DetailsState.Error(e.userMessage(context))
            }
        }
    }

    fun selectSeason(number: Int) {
        _season.value = number
    }

    /** Opens [target] in another player; false when no app can play it or the URL is unavailable. */
    suspend fun openExternal(target: PlayTarget): Boolean {
        val url = vod.streamUrl(target.request) ?: return false
        val title = listOfNotNull(target.request.title, target.request.episodeTitle).joinToString(" – ")
        return ExternalPlayer.open(context, url, title, userAgent, target.startMs)
    }

    fun movieTarget(details: VodDetails, resume: Boolean): PlayTarget {
        val p = movieProgress.value
        val start = if (resume && p != null && !p.finished) p.positionMs else 0
        return PlayTarget(
            VodPlayRequest(VodKind.MOVIE, details.id, null, details.name, details.poster, details.extension ?: "mp4", null, null),
            start,
        )
    }

    fun episodeTarget(details: VodDetails, episode: Episode, resume: Boolean = true): PlayTarget {
        val p = episodeProgress.value[episode.id]
        val start = if (resume && p != null && !p.finished) p.positionMs else 0
        return PlayTarget(
            VodPlayRequest(
                kind = VodKind.SERIES,
                itemId = episode.id,
                seriesId = details.id,
                title = details.name,
                poster = details.poster,
                extension = episode.extension,
                season = episode.season,
                episode = episode.number,
                episodeTitle = episode.title.takeIf { it.isNotBlank() },
            ),
            start,
            episode,
        )
    }

    /**
     * Where a series continues: the last watched episode if unfinished, else the one after it,
     * else the very first episode.
     */
    fun seriesResume(details: VodDetails): PlayTarget? {
        val all = details.seasons.flatMap { it.episodes }
        if (all.isEmpty()) return null
        val last = episodeProgress.value.values.maxByOrNull { it.updatedAt }
            ?: return episodeTarget(details, all.first(), resume = false)
        val idx = all.indexOfFirst { it.id == last.itemId }
        if (idx < 0) return episodeTarget(details, all.first(), resume = false)
        return if (!last.finished) episodeTarget(details, all[idx])
        else episodeTarget(details, all.getOrElse(idx + 1) { all[idx] }, resume = false)
    }
}
