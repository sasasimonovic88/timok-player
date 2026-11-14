package rs.timok.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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

    // Channel number typed on the menus (channel list, movies, series...); same rules as in the player.

    private val _openChannel = MutableSharedFlow<StartChannel>(extraBufferCapacity = 1)
    /** Channel to open fullscreen for a typed number. */
    val openChannel: SharedFlow<StartChannel> = _openChannel

    private val _numberNotFound = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Typed number that matches no channel. */
    val numberNotFound: SharedFlow<String> = _numberNotFound

    private val numberEntry = ChannelNumberEntry(
        scope = viewModelScope,
        channels = channelRepo,
        playlistId = { playlists.activePlaylistNow()?.id },
    ) { result ->
        when (result) {
            is NumberResult.Open -> _openChannel.tryEmit(StartChannel((result.group ?: GroupKey.All).key, result.streamId))
            NumberResult.Previous -> playback.previousChannel?.let { (id, g) -> _openChannel.tryEmit(StartChannel(g.key, id)) }
            is NumberResult.NotFound -> _numberNotFound.tryEmit(result.text)
        }
    }
    val numberInput: StateFlow<String> = numberEntry.input

    fun onDigit(digit: Int, eventTimeMs: Long) = numberEntry.onDigit(digit, eventTimeMs)

    fun clearNumber() = numberEntry.clear()

    /** Opens the typed channel; returns true when a number was pending. */
    fun commitNumber(): Boolean = numberEntry.commit()
}
