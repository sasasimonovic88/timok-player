package rs.timok.player.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import rs.timok.player.data.db.ChannelRow
import rs.timok.player.data.repo.ChannelRepository
import rs.timok.player.data.repo.GroupKey

/** What a typed channel number resolved to. */
sealed interface NumberResult {
    /** Open [streamId]; a null [group] means "stay in the group the viewer is zapping in". */
    data class Open(val group: GroupKey?, val streamId: Long) : NumberResult

    /** A lone 0: back to the previous channel. */
    data object Previous : NumberResult

    data class NotFound(val text: String) : NumberResult
}

/**
 * Channel number typed on the remote, shared by the fullscreen player and the menus so both follow the same
 * rules: digits collect for [TIMEOUT_MS] (or until OK), machine bursts of digits are ignored, a lone 0 means the
 * previous channel, and a number picks the favorite at that position first, then (in the player) a channel with
 * that number in the current group, then the channel with that number.
 */
class ChannelNumberEntry(
    private val scope: CoroutineScope,
    private val channels: ChannelRepository,
    private val playlistId: suspend () -> Long?,
    /** Channel with this number in the group being zapped (player only); checked after the favorites. */
    private val inCurrentGroup: (Int) -> ChannelRow? = { null },
    private val onResult: (NumberResult) -> Unit,
) {
    private val _input = MutableStateFlow("")
    /** Digits typed so far, shown on screen; empty when nothing is pending. */
    val input: StateFlow<String> = _input

    private var job: Job? = null
    private var lastDigitAt = 0L
    private var burst = false

    /**
     * Some air-mouse remotes inject several digits in the same millisecond (e.g. when switching to mouse mode).
     * No person types that fast, so such a run is dropped instead of zapping to it.
     */
    fun onDigit(digit: Int, eventTimeMs: Long) {
        val gap = eventTimeMs - lastDigitAt
        lastDigitAt = eventTimeMs
        if (gap in 0 until BURST_GAP_MS) {
            burst = true
            clear()
            return
        }
        if (burst && gap < BURST_COOLDOWN_MS) return
        burst = false
        if (_input.value.length >= MAX_DIGITS) return
        _input.value += digit.toString()
        job?.cancel()
        job = scope.launch {
            delay(TIMEOUT_MS)
            commit()
        }
    }

    fun clear() {
        job?.cancel()
        _input.value = ""
    }

    /** Resolves the pending number now (OK pressed); returns true when one was pending, so OK is consumed. */
    fun commit(): Boolean {
        val text = _input.value
        if (text.isEmpty()) return false
        clear()
        val number = text.toIntOrNull() ?: return true
        // Channel numbers never start with 0.
        if (number == 0) {
            onResult(NumberResult.Previous)
            return true
        }
        scope.launch {
            val pid = playlistId() ?: return@launch
            val favorite = channels.channels(pid, GroupKey.Favorites).first().getOrNull(number - 1)
            val result = when {
                // Up/down then zaps through the favorites.
                favorite != null -> NumberResult.Open(GroupKey.Favorites, favorite.streamId)
                else -> inCurrentGroup(number)?.let { NumberResult.Open(null, it.streamId) }
                    ?: channels.findByNumber(pid, number)?.let { NumberResult.Open(GroupKey.All, it.streamId) }
                    ?: NumberResult.NotFound(text)
            }
            onResult(result)
        }
        return true
    }

    private companion object {
        const val TIMEOUT_MS = 2_000L
        const val BURST_GAP_MS = 40L
        const val BURST_COOLDOWN_MS = 300L
        const val MAX_DIGITS = 5
    }
}
