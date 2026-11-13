package rs.timok.player.ui.vod

import rs.timok.player.player.StreamInfo
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import rs.timok.player.R
import rs.timok.player.data.repo.VodPlayRequest
import rs.timok.player.data.repo.VodRepository
import rs.timok.player.domain.VodKind
import rs.timok.player.player.AspectMode
import rs.timok.player.player.PlaybackController
import rs.timok.player.player.SleepTimer
import rs.timok.player.player.PlaybackStatus
import rs.timok.player.player.TrackOption
import javax.inject.Inject

@HiltViewModel
class VodPlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
    private val vod: VodRepository,
    private val playback: PlaybackController,
    private val sleepTimer: SleepTimer,
) : ViewModel() {

    private val player get() = playback.livePlayer.player

    private val _request = MutableStateFlow(Json.decodeFromString<VodPlayRequest>(savedState.get<String>("req")!!))
    val request: StateFlow<VodPlayRequest> = _request

    val status: StateFlow<PlaybackStatus> = playback.livePlayer.status
    val streamInfo: StateFlow<StreamInfo> = playback.livePlayer.streamInfo
    val slowDecoding: StateFlow<Boolean> = playback.livePlayer.slowDecoding

    private val _info = MutableStateFlow<VodInfo?>(null)
    /** Poster, year/genre/rating and plot for the control bar; null until loaded (and for catch-up). */
    val info: StateFlow<VodInfo?> = _info
    val tracks: StateFlow<List<TrackOption>> = playback.livePlayer.tracks
    val aspect: StateFlow<AspectMode> = playback.aspect
    val sleepMinutes: StateFlow<Int> = sleepTimer.minutes
    val sleepRemainingMs: StateFlow<Long?> = sleepTimer.remainingMs

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs
    private val _playing = MutableStateFlow(true)
    val playing: StateFlow<Boolean> = _playing

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** True once playback is over with nothing to continue to; the screen then closes. */
    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished

    private var exited = false

    init {
        start(_request.value, savedState.get<Long>("start") ?: 0)
        viewModelScope.launch {
            var sinceSave = 0L
            while (true) {
                delay(TICK_MS)
                _positionMs.value = player.currentPosition.coerceAtLeast(0)
                _durationMs.value = player.duration.takeIf { it > 0 } ?: 0
                _playing.value = player.playWhenReady
                sinceSave += TICK_MS
                if (sinceSave >= SAVE_EVERY_MS && player.isPlaying && _request.value.url == null) {
                    sinceSave = 0
                    vod.saveProgress(_request.value, _positionMs.value, _durationMs.value)
                }
            }
        }
        viewModelScope.launch {
            status.collect { if (it == PlaybackStatus.Ended) onEnded() }
        }
    }

    private fun start(request: VodPlayRequest, startMs: Long) {
        loadInfo(request)
        viewModelScope.launch {
            val url = request.url ?: vod.streamUrl(request) ?: return@launch
            playback.playVod(url, startMs)
        }
    }

    /** Details are usually cached from the details screen; an episode prefers its own plot and still image. */
    private fun loadInfo(request: VodPlayRequest) {
        _info.value = null
        // Catch-up items carry a channel id, not a movie id: there are no details to show.
        if (request.url != null) return
        viewModelScope.launch {
            val details = runCatching {
                if (request.kind == VodKind.MOVIE) vod.details(VodKind.MOVIE, request.itemId)
                else vod.details(VodKind.SERIES, request.seriesId ?: return@launch)
            }.getOrNull() ?: return@launch
            val episode = details.seasons.firstNotNullOfOrNull { s -> s.episodes.firstOrNull { it.id == request.itemId } }
            _info.value = VodInfo(
                poster = details.poster ?: request.poster,
                meta = listOfNotNull(
                    details.year?.take(4)?.takeIf { it.isNotBlank() },
                    details.genre?.takeIf { it.isNotBlank() },
                    details.rating?.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" },
                ).joinToString("  ·  ").ifEmpty { null },
                plot = (episode?.plot ?: details.plot)?.takeIf { it.isNotBlank() },
            )
        }
    }

    fun togglePause() {
        player.playWhenReady = !player.playWhenReady
        _playing.value = player.playWhenReady
    }

    fun setPlaying(play: Boolean) {
        player.playWhenReady = play
        _playing.value = play
    }

    fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0, (duration - 1000).coerceAtLeast(0))
        player.seekTo(target)
        _positionMs.value = target
    }

    fun selectTrack(option: TrackOption) = playback.livePlayer.selectTrack(option)
    fun disableSubtitles() = playback.livePlayer.disableSubtitles()
    fun cycleAspect() = playback.cycleAspect()

    fun cycleSleepTimer() = sleepTimer.cycle()
    fun reload() = playback.reload()

    /** Episodes roll over to the next one; movies and the last episode close the player. */
    private suspend fun onEnded() {
        val current = _request.value
        val duration = player.duration.takeIf { it > 0 } ?: _durationMs.value
        if (current.url == null) vod.saveProgress(current, duration, duration)
        val next = if (current.kind == VodKind.SERIES) runCatching { vod.nextEpisode(current) }.getOrNull() else null
        if (next == null) {
            exit()
            _finished.value = true
            return
        }
        val nextRequest = current.copy(
            itemId = next.id,
            extension = next.extension,
            season = next.season,
            episode = next.number,
            episodeTitle = next.title.takeIf { it.isNotBlank() },
        )
        _request.value = nextRequest
        showMessage(context.getString(R.string.vod_next_episode, nextRequest.episodeTitle ?: "S${next.season} E${next.number}"))
        start(nextRequest, 0)
    }

    /** Saves the resume point and gives the connection back to live TV. */
    fun exit() {
        if (exited) return
        exited = true
        if (_request.value.url == null) vod.saveProgressLater(_request.value, player.currentPosition, player.duration)
        playback.stopVod()
    }

    private fun showMessage(text: String) {
        _message.value = text
        viewModelScope.launch {
            delay(4_000)
            if (_message.value == text) _message.value = null
        }
    }

    override fun onCleared() {
        exit()
    }

    private companion object {
        const val TICK_MS = 500L
        const val SAVE_EVERY_MS = 10_000L
    }
}

/** What the VOD control bar shows about the title besides its name. */
data class VodInfo(val poster: String?, val meta: String?, val plot: String?)
