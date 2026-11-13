package rs.timok.player.player

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import rs.timok.player.data.repo.GroupKey
import rs.timok.player.data.repo.PlaylistRepository
import rs.timok.player.data.settings.SettingsStore
import rs.timok.player.domain.StreamFormat
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * App-wide playback. One player lives behind every screen, so the channel list can show
 * the live channel as its background and going fullscreen does not reopen the stream.
 */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
    @Named("userAgent") val userAgent: String,
    private val playlists: PlaylistRepository,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch { settings.limitVideoToDisplay.collect { livePlayer.setLimitToDisplay(it) } }
    }

    /** Created lazily on the main thread (ExoPlayer requirement). */
    val livePlayer: LivePlayer by lazy { LivePlayer(context, okHttp, userAgent) }

    private val _currentStreamId = MutableStateFlow<Long?>(null)
    /** Channel selected for playback (may still be starting after a zap). */
    val currentStreamId: StateFlow<Long?> = _currentStreamId

    /** See [SettingsStore.textureVideo]. */
    val textureVideo: Flow<Boolean> = settings.textureVideo

    private val _aspect = MutableStateFlow(AspectMode.FIT)
    val aspect: StateFlow<AspectMode> = _aspect

    fun cycleAspect() {
        _aspect.value = AspectMode.entries[(_aspect.value.ordinal + 1) % AspectMode.entries.size]
    }

    /** Reopens the current stream, e.g. when the picture froze. */
    fun reload() {
        if (playingId != null || vodActive) livePlayer.resume()
    }

    private var playingId: Long? = null
    private var group: GroupKey = GroupKey.All
    private var playJob: Job? = null
    private var pausedInBackground = false

    // VOD replaces the live stream (one connection); the live channel comes back afterwards.
    private var vodActive = false
    private var liveBeforeVod: Pair<Long, GroupKey>? = null

    /** Plays a movie or episode from [startMs], closing the live stream first. */
    fun playVod(url: String, startMs: Long) {
        playJob?.cancel()
        if (!vodActive) liveBeforeVod = _currentStreamId.value?.let { it to group }
        vodActive = true
        playingId = null
        _currentStreamId.value = null
        livePlayer.playVod(url, startMs)
    }

    /** Ends VOD and, if background video is on, returns to the channel that was playing before. */
    fun stopVod() {
        if (!vodActive) return
        vodActive = false
        livePlayer.stop()
        val previous = liveBeforeVod
        liveBeforeVod = null
        scope.launch {
            if (previous != null && settings.backgroundPlayback.first()) play(previous.first, previous.second)
        }
    }

    // Channel stopped while the movie and series screens are open; it comes back on the TV screens.
    private var pausedForBrowse: Pair<Long, GroupKey>? = null

    /** Stops the live channel behind the movie/series screens (they show a still backdrop). */
    fun pauseLiveForBrowse() {
        if (vodActive) return
        val id = _currentStreamId.value ?: return
        pausedForBrowse = id to group
        stop()
    }

    /** Restarts the channel stopped by [pauseLiveForBrowse], if background video is on. */
    fun resumeLiveAfterBrowse() {
        val previous = pausedForBrowse ?: return
        pausedForBrowse = null
        scope.launch { if (settings.backgroundPlayback.first()) play(previous.first, previous.second) }
    }

    /** Last channel asked for and the group it was zapped in; kept after stop, for the channel list's focus. */
    var lastPlayed: Pair<Long, GroupKey>? = null
        private set

    /** Channel that was actually open before the current one (quick zaps in between do not count). */
    var previousChannel: Pair<Long, GroupKey>? = null
        private set
    private var lastOpened: Pair<Long, GroupKey>? = null

    /** Starts [streamId]; with [immediate] false, waits briefly so fast zapping opens only the last channel. */
    fun play(streamId: Long, group: GroupKey, immediate: Boolean = true) {
        _currentStreamId.value = streamId
        this.group = group
        lastPlayed = streamId to group
        playJob?.cancel()
        playJob = scope.launch {
            if (!immediate) delay(ZAP_DEBOUNCE_MS)
            if (streamId == playingId) return@launch
            val playlist = playlists.activePlaylistNow() ?: return@launch
            val preferred = playlists.preferredFormat(playlist)
            val fallback = if (preferred == StreamFormat.HLS) StreamFormat.TS else StreamFormat.HLS
            livePlayer.play(
                listOf(
                    playlists.streamUrl(playlist, streamId, preferred),
                    playlists.streamUrl(playlist, streamId, fallback),
                )
            )
            playingId = streamId
            lastOpened?.let { if (it.first != streamId) previousChannel = it }
            lastOpened = streamId to group
            settings.setLastWatched(streamId, group.key)
            settings.addRecentChannel(playlist.id, streamId)
        }
    }

    /** Stops playback and forgets the channel. */
    fun stop() {
        playJob?.cancel()
        if (playingId != null || _currentStreamId.value != null) livePlayer.stop()
        playingId = null
        _currentStreamId.value = null
    }

    /** Stops live TV and VOD alike without returning to a previous channel (sleep timer). */
    fun stopAll() {
        playJob?.cancel()
        vodActive = false
        liveBeforeVod = null
        livePlayer.stop()
        playingId = null
        _currentStreamId.value = null
    }

    /** Called when leaving fullscreen: keep the stream only if background video is enabled. */
    fun onLeaveFullscreen() {
        scope.launch { if (!settings.backgroundPlayback.first()) stop() }
    }

    /** Frees the connection while the app is not visible. */
    fun onAppBackground() {
        if (vodActive) {
            livePlayer.stop()
            pausedInBackground = true
            return
        }
        if (_currentStreamId.value == null) return
        playJob?.cancel()
        livePlayer.stop()
        playingId = null
        pausedInBackground = true
    }

    fun onAppForeground() {
        if (!pausedInBackground) return
        pausedInBackground = false
        if (vodActive) {
            livePlayer.resume()
            return
        }
        _currentStreamId.value?.let { play(it, group) }
    }

    private companion object {
        const val ZAP_DEBOUNCE_MS = 400L
    }
}
