package rs.timok.player.ui.channels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rs.timok.player.R
import rs.timok.player.data.db.CategoryEntity
import rs.timok.player.data.db.ChannelRow
import rs.timok.player.data.db.PlaylistEntity
import rs.timok.player.data.repo.ChannelRepository
import rs.timok.player.data.repo.GroupKey
import rs.timok.player.data.repo.PlaylistRepository
import rs.timok.player.data.settings.SettingsStore
import rs.timok.player.player.PlaybackController
import rs.timok.player.ui.components.userMessage
import javax.inject.Inject

/** Favorites reorder session: a working copy that is saved on OK or dropped on Back. */
data class MoveState(val items: List<ChannelRow>, val movingId: Long)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChannelsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlists: PlaylistRepository,
    private val channels: ChannelRepository,
    private val settings: SettingsStore,
    private val playback: PlaybackController,
) : ViewModel() {

    val playlist: StateFlow<PlaylistEntity?> =
        playlists.activePlaylist.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val playlistId = playlist.map { it?.id }.distinctUntilChanged()

    private val _group = MutableStateFlow<GroupKey?>(null)
    val group: StateFlow<GroupKey?> = _group

    val categories: StateFlow<List<CategoryEntity>> = playlistId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else channels.categories(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Null until the first query returns, so the UI can tell "loading" from "empty". */
    val rows: StateFlow<List<ChannelRow>?> = combine(playlistId, _group.filterNotNull()) { id, g -> id to g }
        // No playlist yet means "still loading" (null), not an empty group.
        .flatMapLatest { (id, g) -> if (id == null) emptyFlow() else channels.channels(id, g) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _move = MutableStateFlow<MoveState?>(null)
    val move: StateFlow<MoveState?> = _move

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /** Channel currently playing (in the background or fullscreen). */
    val playingId: StateFlow<Long?> = playback.currentStreamId

    /** Channel that should get focus when the screen is (re)entered. */
    var focusTargetId: Long? = null

    init {
        viewModelScope.launch {
            val last = settings.lastGroup.first()
            val lastStream = settings.lastStreamId()
            focusTargetId = lastStream
            val g = GroupKey.parse(last ?: GroupKey.All.key)
            _group.value = g
            // Resume the last channel behind the list, like a TV that remembers where it was.
            if (lastStream != null && playback.currentStreamId.value == null && settings.backgroundPlayback.first()) {
                playback.play(lastStream, g)
            }
        }
    }

    /**
     * Called when the list is shown again: channels zapped in the fullscreen player (maybe in another group,
     * e.g. favorites by number) move the list and its focus to that channel.
     */
    fun followPlayer() {
        val (streamId, group) = playback.lastPlayed ?: return
        if (_move.value != null) return
        focusTargetId = streamId
        if (_group.value != null && _group.value != group) selectGroup(group)
    }

    fun selectGroup(g: GroupKey) {
        if (_group.value == g || _move.value != null) return
        _group.value = g
        viewModelScope.launch { settings.setLastGroup(g.key) }
    }

    fun onLongPress(row: ChannelRow) {
        val pid = playlist.value?.id ?: return
        val current = rows.value ?: return
        if (_group.value == GroupKey.Favorites) {
            _move.value = MoveState(current, row.streamId)
        } else {
            viewModelScope.launch {
                val added = channels.toggleFavorite(pid, row.streamId)
                showMessage(context.getString(if (added) R.string.favorite_added else R.string.favorite_removed))
            }
        }
    }

    fun moveBy(delta: Int) = _move.update { m ->
        m ?: return@update null
        val from = m.items.indexOfFirst { it.streamId == m.movingId }
        val to = from + delta
        if (from < 0 || to !in m.items.indices) return@update m
        val list = m.items.toMutableList()
        list.add(to, list.removeAt(from))
        // Favorites show their position as the number; keep it live while moving.
        m.copy(items = list.mapIndexed { i, r -> r.copy(number = i + 1) })
    }

    fun commitMove() {
        val m = _move.value ?: return
        val pid = playlist.value?.id ?: return
        focusTargetId = m.movingId
        viewModelScope.launch {
            channels.reorderFavorites(pid, m.items.map { it.streamId })
            _move.value = null
        }
    }

    fun cancelMove() {
        _move.value?.let { focusTargetId = it.movingId }
        _move.value = null
    }

    fun refresh() {
        val pid = playlist.value?.id ?: return
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                playlists.refreshChannels(pid)
                playlists.refreshEpgNow(pid)
            } catch (e: Exception) {
                showMessage(e.userMessage(context))
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * First OK plays the channel behind the list; OK on the channel that is already playing goes fullscreen.
     * Returns true when the caller should open the fullscreen player.
     */
    suspend fun onChannelClick(row: ChannelRow): Boolean {
        focusTargetId = row.streamId
        if (!settings.backgroundPlayback.first()) return true
        if (playback.currentStreamId.value == row.streamId) return true
        playback.play(row.streamId, _group.value ?: GroupKey.All)
        return false
    }

    /** Group key and stream of the channel playing behind the list, for going fullscreen on it. */
    fun fullscreenTarget(): Pair<String, Long>? {
        val (streamId, group) = playback.lastPlayed ?: return null
        return if (streamId == playback.currentStreamId.value) group.key to streamId else null
    }

    private fun showMessage(text: String) {
        _message.value = text
        viewModelScope.launch {
            kotlinx.coroutines.delay(2_500)
            if (_message.value == text) _message.value = null
        }
    }
}
