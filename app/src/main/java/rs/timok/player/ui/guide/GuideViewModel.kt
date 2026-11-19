package rs.timok.player.ui.guide

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rs.timok.player.R
import rs.timok.player.data.db.ChannelRow
import rs.timok.player.data.db.ProgramEntity
import rs.timok.player.data.repo.ChannelRepository
import rs.timok.player.data.repo.GroupKey
import rs.timok.player.data.repo.PlaylistRepository
import rs.timok.player.data.repo.VodPlayRequest
import rs.timok.player.ui.program.CatchupFinder
import javax.inject.Inject

data class GuideGroup(val key: GroupKey, val title: String)

/**
 * TV guide grid (channels × time) for one group. Programmes are loaded only for the channels around the focused
 * row, so a group of thousands of channels never loads its whole guide at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GuideViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
    private val playlists: PlaylistRepository,
    private val channels: ChannelRepository,
    private val reminderManager: rs.timok.player.data.reminder.ReminderManager,
) : ViewModel() {

    /** Keys ("streamId@startMs") of programmes with a reminder, to mark them in the grid. */
    val reminderKeys: StateFlow<Set<String>> = reminderManager.reminders
        .map { list -> list.mapTo(HashSet()) { it.key } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Sets or removes the reminder for [program] on [row]; returns true when it is now set. */
    fun toggleReminder(row: ChannelRow, program: ProgramEntity): Boolean = reminderManager.toggle(
        rs.timok.player.data.reminder.Reminder(row.streamId, _group.value.key, row.name, program.title, program.startMs)
    )

    /** Channel to start on (the one playing), if any. */
    val startStreamId: Long? = savedState.get<Long>("streamId")?.takeIf { it > 0 }

    private val _group = MutableStateFlow(
        // "Recently watched" reorders itself; the guide starts on Favorites instead.
        GroupKey.parse(savedState.get<String>("group")).let { if (it == GroupKey.Recent) GroupKey.Favorites else it }
    )
    val group: StateFlow<GroupKey> = _group

    private val playlistId = playlists.activePlaylist.map { it?.id }

    /** Favorites, all channels and the visible server categories, in the channel list's order. */
    val groups: StateFlow<List<GuideGroup>> = playlistId
        .flatMapLatest { pid -> if (pid == null) flowOf(emptyList()) else channels.categories(pid) }
        .map { cats ->
            listOf(
                GuideGroup(GroupKey.Favorites, context.getString(R.string.group_favorites)),
                GuideGroup(GroupKey.All, context.getString(R.string.group_all)),
            ) + cats.map { GuideGroup(GroupKey.Category(it.categoryId), it.name) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Null until the first query returns. */
    val rows: StateFlow<List<ChannelRow>?> = combine(playlistId, _group) { pid, g -> pid to g }
        .flatMapLatest { (pid, g) -> if (pid == null) flowOf(null) else channels.channels(pid, g) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Programmes per guide key, for the loaded rows only. */
    private val _programs = MutableStateFlow<Map<String, List<ProgramEntity>>>(emptyMap())
    val programs: StateFlow<Map<String, List<ProgramEntity>>> = _programs

    val now: StateFlow<Long> = channels.minuteTicker
        .stateIn(viewModelScope, SharingStarted.Eagerly, System.currentTimeMillis())

    /** Earliest time the guide scrolls to (the guide keeps about three hours of the past). */
    val minMs: Long = System.currentTimeMillis() - PAST_MS
    val maxMs: Long = System.currentTimeMillis() + FUTURE_MS

    private var loaded: IntRange = IntRange.EMPTY
    /** Stream ids of the list the rows were loaded for (the list object changes every minute with now/next). */
    private var loadedFor: List<Long>? = null
    private var loadJob: Job? = null

    fun selectGroup(key: GroupKey) {
        if (key == _group.value) return
        loaded = IntRange.EMPTY
        loadedFor = null
        _programs.value = emptyMap()
        _group.value = key
    }

    /** Makes sure the programmes of the rows around [index] are loaded. */
    fun ensureLoaded(list: List<ChannelRow>, index: Int) {
        if (list.isEmpty()) return
        // Still well inside the loaded rows (list edges count as covered): nothing to do.
        val ids = list.map { it.streamId }
        val covered = loadedFor == ids && !loaded.isEmpty() &&
            (index - MARGIN).coerceAtLeast(0) >= loaded.first &&
            (index + MARGIN).coerceAtMost(list.lastIndex) <= loaded.last
        if (covered) return
        val range = (index - WINDOW).coerceAtLeast(0)..(index + WINDOW).coerceAtMost(list.lastIndex)
        loaded = range
        loadedFor = ids
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val pid = playlists.activePlaylistNow()?.id ?: return@launch
            val keys = list.subList(range.first, range.last + 1).mapNotNull { it.epgChannelId }.distinct()
            val result = channels.programsFor(pid, keys, minMs, maxMs).mapValues { (_, l) -> rs.timok.player.epg.cleanSchedule(l) }
            _programs.value = result
        }
    }

    private val catchup = CatchupFinder(playlists)

    suspend fun archiveRequest(row: ChannelRow, program: ProgramEntity): VodPlayRequest? = catchup.request(row, program)


    private companion object {
        const val PAST_MS = 3 * 60 * 60 * 1000L
        const val FUTURE_MS = 4 * 24 * 60 * 60 * 1000L
        /** Rows loaded on each side of the focused one, and how close to the edge a reload starts. */
        const val WINDOW = 25
        const val MARGIN = 8
    }
}
