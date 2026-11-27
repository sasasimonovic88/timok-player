package rs.timok.player.player

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
    private val parental: rs.timok.player.data.parental.ParentalControl,
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

    /** Opens the current live channel from scratch (new decoder and buffer), e.g. after a player setting changed. */
    fun restartLive() {
        if (vodActive) return
        val id = _currentStreamId.value ?: return
        playingId = null
        play(id, group)
    }

    private var playingId: Long? = null
    private var group: GroupKey = GroupKey.All
    private var playJob: Job? = null
    private var pausedInBackground = false

    // VOD replaces the live stream (one connection); the live channel comes back afterwards.
    private var vodActive = false
    private var liveBeforeVod: Pair<Long, GroupKey>? = null

    // Movies are only reached through unlocked categories; one started after the PIN was entered asks for it again
    // when the app comes back from the background (the TV may have been switched off and on by someone else).
    private var vodNeedsPin = false

    private val _pinCancelled = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The PIN prompt for a locked channel or movie was dismissed, so nothing plays: the player screens close. */
    val pinCancelled: SharedFlow<Unit> = _pinCancelled

    /** Plays a movie or episode from [startMs], closing the live stream first. */
    fun playVod(url: String, startMs: Long) {
        playJob?.cancel()
        vodNeedsPin = parental.enabled.value && !parental.active.value
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
            if (previous != null && settings.backgroundPlayback.first()) play(previous.first, previous.second, askPin = false)
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

    /**
     * Restarts the channel stopped by [pauseLiveForBrowse], if background video is on. A channel asked for in the
     * meantime (e.g. a number typed on the movies screen) wins: the old one must not replace it.
     */
    fun resumeLiveAfterBrowse() {
        val previous = pausedForBrowse ?: return
        pausedForBrowse = null
        scope.launch {
            if (settings.backgroundPlayback.first() && _currentStreamId.value == null) play(previous.first, previous.second, askPin = false)
        }
    }

    /** Last channel asked for and the group it was zapped in; kept after stop, for the channel list's focus. */
    var lastPlayed: Pair<Long, GroupKey>? = null
        private set

    /** Channel that was actually open before the current one (quick zaps in between do not count). */
    var previousChannel: Pair<Long, GroupKey>? = null
        private set
    private var lastOpened: Pair<Long, GroupKey>? = null

    /**
     * Starts [streamId]; with [immediate] false, waits briefly so fast zapping opens only the last channel.
     * A channel of a locked category asks for the PIN first, or with [askPin] false (automatic resumes) is skipped.
     */
    fun play(streamId: Long, group: GroupKey, immediate: Boolean = true, askPin: Boolean = true) {
        if (!parental.active.value) {
            playUnlocked(streamId, group, immediate)
            return
        }
        scope.launch {
            val pid = playlists.activePlaylistNow()?.id ?: return@launch
            if (!parental.isChannelLocked(pid, streamId)) {
                playUnlocked(streamId, group, immediate)
                return@launch
            }
            // The channel kept from before the app went to the background must not look like it is playing.
            if (!askPin) {
                if (_currentStreamId.value == streamId) stop()
                return@launch
            }
            parental.requestUnlock(
                onCancel = {
                    if (_currentStreamId.value == streamId) stop()
                    _pinCancelled.tryEmit(Unit)
                },
            ) { playUnlocked(streamId, group, immediate) }
        }
    }

    private fun playUnlocked(streamId: Long, group: GroupKey, immediate: Boolean) {
        // An explicit channel replaces the one paused behind the movie screens.
        pausedForBrowse = null
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
            settings.setLastWatched(playlist.id, streamId, group.key)
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

    /** Another account became active: stop everything and forget channels of the old one. */
    fun resetForAccountSwitch() {
        stopAll()
        // No frozen picture of the old account's channel behind the new channel list.
        livePlayer.clear()
        pausedForBrowse = null
        pausedInBackground = false
        lastPlayed = null
        previousChannel = null
        lastOpened = null
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
            if (vodNeedsPin && parental.active.value) {
                parental.requestUnlock(
                    onCancel = {
                        stopAll()
                        _pinCancelled.tryEmit(Unit)
                    },
                ) { livePlayer.resume() }
            } else {
                livePlayer.resume()
            }
            return
        }
        // A locked channel asks for the PIN again (the app locked itself in the background).
        _currentStreamId.value?.let { play(it, group) }
    }

    private companion object {
        const val ZAP_DEBOUNCE_MS = 400L
    }
}
