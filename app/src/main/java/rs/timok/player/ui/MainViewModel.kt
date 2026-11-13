package rs.timok.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import rs.timok.player.data.repo.ChannelRepository
import rs.timok.player.data.repo.GroupKey
import rs.timok.player.data.repo.PlaylistRepository
import rs.timok.player.data.settings.SettingsStore
import javax.inject.Inject

/** Channel to open fullscreen right after launch. */
data class StartChannel(val group: String, val streamId: Long)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val playlists: PlaylistRepository,
    private val settings: SettingsStore,
    private val channelRepo: ChannelRepository,
    /** Self-update (sideload build); its dialog is shown above every screen. */
    val updates: rs.timok.player.data.update.UpdateManager,
    private val playback: rs.timok.player.player.PlaybackController,
) : ViewModel() {

    /** Null while deciding; the splash screen stays up until then. */
    private val _startRoute = MutableStateFlow<String?>(null)
    val startRoute: StateFlow<String?> = _startRoute

    /** Last watched channel, opened fullscreen on top of the channel list once per launch. */
    private val _startChannel = MutableStateFlow<StartChannel?>(null)
    val startChannel: StateFlow<StartChannel?> = _startChannel

    init {
        viewModelScope.launch {
            val playlist = playlists.activePlaylistNow()
            if (playlist != null) {
                settings.lastStreamId()?.let { id ->
                    _startChannel.value = StartChannel(settings.lastGroup.first() ?: GroupKey.All.key, id)
                }
            }
            _startRoute.value = if (playlist == null) Routes.LOGIN else Routes.CHANNELS
        }
    }

    /** Every return to the screen (also after the box wakes up), not only a fresh start. */
    fun onForeground() {
        viewModelScope.launch { playlists.refreshIfStale() }
        updates.checkIfDue()
    }

    fun consumeStartChannel() {
        _startChannel.value = null
    }

    // Channel number typed on the menus (channel list, movies, series...). The fullscreen player has its own entry.

    private val _numberInput = MutableStateFlow("")
    val numberInput: StateFlow<String> = _numberInput

    private val _openChannel = MutableSharedFlow<StartChannel>(extraBufferCapacity = 1)
    /** Channel to open fullscreen for a typed number. */
    val openChannel: SharedFlow<StartChannel> = _openChannel

    private val _numberNotFound = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Typed number that matches no channel. */
    val numberNotFound: SharedFlow<String> = _numberNotFound

    private var numberJob: Job? = null
    private var lastDigitAt = 0L
    private var digitBurst = false

    /** Same rules as in the player: machine bursts of digits (air-mouse remotes) are ignored. */
    fun onDigit(d: Int, eventTimeMs: Long) {
        val gap = eventTimeMs - lastDigitAt
        lastDigitAt = eventTimeMs
        if (gap in 0 until BURST_GAP_MS) {
            digitBurst = true
            clearNumber()
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

    fun clearNumber() {
        numberJob?.cancel()
        _numberInput.value = ""
    }

    /**
     * Opens the typed channel; returns true when a number was pending. As in the player, numbers pick
     * favorites first (by their manual position); beyond the favorites they are channel numbers.
     */
    fun commitNumber(): Boolean {
        val text = _numberInput.value
        if (text.isEmpty()) return false
        clearNumber()
        val number = text.toIntOrNull() ?: return true
        if (number == 0) {
            // A lone 0 is the previous channel, as in the player.
            playback.previousChannel?.let { (id, g) -> _openChannel.tryEmit(StartChannel(g.key, id)) }
            return true
        }
        viewModelScope.launch {
            val pid = playlists.activePlaylistNow()?.id ?: return@launch
            val favorite = channelRepo.channels(pid, GroupKey.Favorites).first().getOrNull(number - 1)
            val target = when {
                favorite != null -> StartChannel(GroupKey.Favorites.key, favorite.streamId)
                else -> channelRepo.findByNumber(pid, number)?.let { StartChannel(GroupKey.All.key, it.streamId) }
            }
            if (target != null) _openChannel.emit(target) else _numberNotFound.emit(text)
        }
        return true
    }

    private companion object {
        const val NUMBER_TIMEOUT_MS = 2_000L
        const val BURST_GAP_MS = 40L
        const val BURST_COOLDOWN_MS = 300L
    }
}
