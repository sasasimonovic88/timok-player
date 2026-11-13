package rs.timok.player.ui.player

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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rs.timok.player.R
import rs.timok.player.data.db.ChannelRow
import rs.timok.player.data.db.PlaylistEntity
import rs.timok.player.data.db.ProgramEntity
import rs.timok.player.data.repo.ChannelRepository
import rs.timok.player.data.repo.GroupKey
import rs.timok.player.data.repo.PlaylistRepository
import rs.timok.player.player.ExternalPlayer
import rs.timok.player.player.PlaybackController
import rs.timok.player.player.SleepTimer
import rs.timok.player.player.PlaybackStatus
import rs.timok.player.player.StreamInfo
import rs.timok.player.player.AspectMode
import rs.timok.player.player.TrackOption
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
    private val playlists: PlaylistRepository,
    private val channelRepo: ChannelRepository,
    private val playback: PlaybackController,
    private val sleepTimer: SleepTimer,
) : ViewModel() {

    val status: StateFlow<PlaybackStatus> = playback.livePlayer.status
    val streamInfo: StateFlow<StreamInfo> = playback.livePlayer.streamInfo
    val slowDecoding: StateFlow<Boolean> = playback.livePlayer.slowDecoding
    val tracks: StateFlow<List<TrackOption>> = playback.livePlayer.tracks
    val aspect: StateFlow<AspectMode> = playback.aspect
    val sleepMinutes: StateFlow<Int> = sleepTimer.minutes
    val sleepRemainingMs: StateFlow<Long?> = sleepTimer.remainingMs

    private val playlist = MutableStateFlow<PlaylistEntity?>(null)
    private val group = MutableStateFlow(GroupKey.parse(savedState.get<String>("group")))
    /** Group the player zaps in, for screens opened from it. */
    val groupKey: String get() = group.value.key

    /** Channels of the group the player was opened from; up/down zaps within it. */
    val channels: StateFlow<List<ChannelRow>> = combine(playlist.filterNotNull(), group) { p, g -> p.id to g }
        .flatMapLatest { (id, g) -> channelRepo.channels(id, g) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val current: StateFlow<ChannelRow?> = combine(channels, playback.currentStreamId) { list, id ->
        list.firstOrNull { it.streamId == id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val nowNext: StateFlow<List<ProgramEntity>> = combine(playlist.filterNotNull(), current) { p, c -> p.id to c?.epgChannelId }
        .distinctUntilChanged()
        .flatMapLatest { (id, key) -> if (key == null) flowOf(emptyList()) else channelRepo.nowNext(id, key) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _numberInput = MutableStateFlow("")
    val numberInput: StateFlow<String> = _numberInput

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Bumped on every channel change so the UI re-shows the info bar. */
    private val _zapCounter = MutableStateFlow(0)
    val zapCounter: StateFlow<Int> = _zapCounter

    private var numberJob: Job? = null

    init {
        // A no-op when the channel is already playing in the background.
        savedState.get<Long>("streamId")?.let { playback.play(it, group.value) }
        viewModelScope.launch { playlist.value = playlists.activePlaylistNow() }
        viewModelScope.launch { playback.currentStreamId.collect { if (it != null) lastLiveId = it } }
        // If the opened group no longer contains the channel (e.g. it was un-favorited), fall back to all channels.
        viewModelScope.launch {
            channels.map { list -> list.isNotEmpty() && list.none { it.streamId == playback.currentStreamId.value } }
                .distinctUntilChanged()
                .collect { missing -> if (missing && group.value != GroupKey.All) group.value = GroupKey.All }
        }
    }

    fun zap(delta: Int) {
        val list = channels.value
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.streamId == playback.currentStreamId.value }
        val next = list[((if (idx < 0) 0 else idx + delta) % list.size + list.size) % list.size]
        _zapCounter.value++
        playback.play(next.streamId, group.value, immediate = false)
    }

    /** Last live channel of this screen, to come back to after catch-up. */
    private var lastLiveId: Long? = savedState.get<Long>("streamId")

    fun resumeLive() {
        val id = lastLiveId ?: return
        if (playback.currentStreamId.value == null) playback.play(id, group.value)
    }

    fun selectChannel(streamId: Long) {
        _zapCounter.value++
        playback.play(streamId, group.value)
    }

    /** Event time of the previous digit and whether the current run of digits is a machine burst. */
    private var lastDigitAt = 0L
    private var digitBurst = false

    /**
     * Some air-mouse remotes inject several digits in the same millisecond (e.g. when switching to
     * mouse mode). No person types that fast, so such a run is dropped instead of zapping to it.
     */
    fun onDigit(d: Int, eventTimeMs: Long) {
        val gap = eventTimeMs - lastDigitAt
        lastDigitAt = eventTimeMs
        if (gap in 0 until BURST_GAP_MS) {
            digitBurst = true
            numberJob?.cancel()
            _numberInput.value = ""
            return
        }
        if (digitBurst && gap < BURST_COOLDOWN_MS) return
        digitBurst = false
        if (_numberInput.value.length >= 5) return
        _numberInput.value += d.toString()
        numberJob?.cancel()
        numberJob = viewModelScope.launch {
            delay(NUMBER_TIMEOUT_MS)
            commitNumber()
        }
    }

    /**
     * Returns true when a number was pending (so OK is consumed by number entry).
     * Numbers pick favorites first (by their manual position); beyond the favorites they are channel numbers.
     */
    fun commitNumber(): Boolean {
        val text = _numberInput.value
        if (text.isEmpty()) return false
        numberJob?.cancel()
        _numberInput.value = ""
        val number = text.toIntOrNull() ?: return true
        // Channel numbers never start with 0: a lone 0 goes back to the previous channel.
        if (number == 0) {
            previousChannel()
            return true
        }
        val pid = playlist.value?.id ?: return true
        viewModelScope.launch {
            val favorite = channelRepo.channels(pid, GroupKey.Favorites).first().getOrNull(number - 1)
            if (favorite != null) {
                // Up/down then zaps through the favorites.
                group.value = GroupKey.Favorites
                selectChannel(favorite.streamId)
                return@launch
            }
            val inGroup = channels.value.firstOrNull { it.number == number }
            if (inGroup != null) {
                selectChannel(inGroup.streamId)
                return@launch
            }
            val channel = channelRepo.findByNumber(pid, number)
            if (channel == null) {
                showMessage(context.getString(R.string.channel_not_found, text))
            } else {
                group.value = GroupKey.All
                selectChannel(channel.streamId)
            }
        }
        return true
    }

    /** Back to the channel watched before this one (0 or the remote's "last channel" key). */
    fun previousChannel() {
        val (id, g) = playback.previousChannel ?: run {
            showMessage(context.getString(R.string.previous_channel_none))
            return
        }
        group.value = g
        selectChannel(id)
    }

    /** Yellow key: subtitles on (first track) or off. */
    fun toggleSubtitles() {
        val text = tracks.value.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
        when {
            text.isEmpty() -> showMessage(context.getString(R.string.subtitles_none))
            text.any { it.selected } -> {
                disableSubtitles()
                showMessage(context.getString(R.string.subtitles_off))
            }
            else -> {
                selectTrack(text.first())
                showMessage(context.getString(R.string.subtitles_on, text.first().label ?: text.first().language ?: ""))
            }
        }
    }

    /** Blue key: next picture format, named on screen. */
    fun cycleAspectWithMessage() {
        cycleAspect()
        val name = when (aspect.value) {
            rs.timok.player.player.AspectMode.FIT -> R.string.aspect_fit
            rs.timok.player.player.AspectMode.ZOOM -> R.string.aspect_zoom
            rs.timok.player.player.AspectMode.STRETCH -> R.string.aspect_stretch
        }
        showMessage(context.getString(name))
    }

    fun toggleFavorite() {
        val pid = playlist.value?.id ?: return
        val id = playback.currentStreamId.value ?: return
        viewModelScope.launch {
            val added = channelRepo.toggleFavorite(pid, id)
            showMessage(context.getString(if (added) R.string.favorite_added else R.string.favorite_removed))
        }
    }

    fun selectTrack(option: TrackOption) = playback.livePlayer.selectTrack(option)

    fun disableSubtitles() = playback.livePlayer.disableSubtitles()

    fun cycleAspect() = playback.cycleAspect()

    fun cycleSleepTimer() = sleepTimer.cycle()

    fun reload() = playback.reload()

    /**
     * Opens the current channel in another player. Our stream is closed first: many accounts allow one
     * connection. The channel restarts here when the viewer comes back (see [resumeLive]).
     */
    fun openExternal() {
        val id = playback.currentStreamId.value ?: lastLiveId ?: return
        viewModelScope.launch {
            val p = playlist.value ?: playlists.activePlaylistNow() ?: return@launch
            val url = playlists.streamUrl(p, id, playlists.preferredFormat(p))
            val name = channels.value.firstOrNull { it.streamId == id }?.name.orEmpty()
            playback.stop()
            if (!ExternalPlayer.open(context, url, name, playback.userAgent)) {
                showMessage(context.getString(R.string.external_player_none))
                resumeLive()
            }
        }
    }

    private fun showMessage(text: String) {
        _message.value = text
        viewModelScope.launch {
            delay(2_500)
            if (_message.value == text) _message.value = null
        }
    }

    private companion object {
        const val NUMBER_TIMEOUT_MS = 2_000L
        const val BURST_GAP_MS = 40L
        const val BURST_COOLDOWN_MS = 300L
    }
}
