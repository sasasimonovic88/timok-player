package rs.timok.player.ui.program

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rs.timok.player.data.db.ChannelRow
import rs.timok.player.data.db.ProgramEntity
import rs.timok.player.data.repo.ChannelRepository
import rs.timok.player.data.repo.GroupKey
import rs.timok.player.data.repo.PlaylistRepository
import rs.timok.player.data.repo.VodPlayRequest
import javax.inject.Inject

sealed interface ScheduleState {
    data object Loading : ScheduleState
    data class Ready(val programs: List<ProgramEntity>) : ScheduleState
}

/** The full stored guide of one channel: what ran in the last hours and the coming days. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProgramViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val playlists: PlaylistRepository,
    private val channels: ChannelRepository,
) : ViewModel() {

    val streamId: Long = savedState.get<Long>("streamId") ?: 0
    /** Group the channel was opened from; playing it keeps up/down zapping inside that group. */
    val groupKey: String = savedState.get<String>("group") ?: GroupKey.All.key

    private val _channel = MutableStateFlow<ChannelRow?>(null)
    val channel: StateFlow<ChannelRow?> = _channel

    /** Fixed when the screen opens, so the list does not jump while it is being read. */
    private val from = System.currentTimeMillis() - PAST_MS

    val schedule: StateFlow<ScheduleState> = _channel.filterNotNull()
        .flatMapLatest { row ->
            val pid = playlists.activePlaylistNow()?.id
            val key = row.epgChannelId
            if (pid == null || key == null) flowOf(emptyList()) else channels.schedule(pid, key, from)
        }
        .map { list -> ScheduleState.Ready(dropOverlaps(list)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScheduleState.Loading)

    /** Current minute; moves the "now" marker and progress while the screen stays open. */
    val now: StateFlow<Long> = channels.minuteTicker
        .stateIn(viewModelScope, SharingStarted.Eagerly, System.currentTimeMillis())

    init {
        viewModelScope.launch {
            val pid = playlists.activePlaylistNow()?.id ?: return@launch
            _channel.value = channels.channelRow(pid, streamId, GroupKey.parse(groupKey))
        }
    }

    private val catchup = CatchupFinder(playlists)

    /** Catch-up request for a programme that already ran, or null when the server's archive does not have it. */
    suspend fun archiveRequest(program: ProgramEntity): VodPlayRequest? {
        val row = _channel.value ?: return null
        return catchup.request(row, program)
    }

    /** Sloppy guides repeat entries or overlap; keep one programme per start time, in order. */
    private fun dropOverlaps(list: List<ProgramEntity>): List<ProgramEntity> {
        val out = ArrayList<ProgramEntity>(list.size)
        for (p in list) {
            val last = out.lastOrNull()
            if (last != null && p.startMs == last.startMs) continue
            out += p
        }
        return out
    }

    private companion object {
        /** The guide keeps about three hours of the past; show what is there. */
        const val PAST_MS = 3 * 60 * 60 * 1000L
    }
}
