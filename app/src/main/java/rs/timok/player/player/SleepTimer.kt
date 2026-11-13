package rs.timok.player.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sleep timer for live TV and VOD. Counts down app-wide (survives screen changes); the activity
 * warns during the last minute and shuts playback down when [expired] fires.
 */
@Singleton
class SleepTimer @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _minutes = MutableStateFlow(0)
    /** Chosen duration; 0 means off. */
    val minutes: StateFlow<Int> = _minutes

    private val _remainingMs = MutableStateFlow<Long?>(null)
    /** Time left while running, updated every second; null when off. */
    val remainingMs: StateFlow<Long?> = _remainingMs

    private val _expired = Channel<Unit>(Channel.CONFLATED)
    val expired = _expired.receiveAsFlow()

    /** Off → 15 → 30 → 60 → 90 → 120 → off. */
    fun cycle() {
        val next = OPTIONS[(OPTIONS.indexOf(_minutes.value) + 1) % OPTIONS.size]
        set(next)
    }

    /** Restarts the countdown with the current duration (the user is still watching). */
    fun extend() = set(_minutes.value)

    fun cancel() = set(0)

    private fun set(minutes: Int) {
        job?.cancel()
        _minutes.value = minutes
        if (minutes == 0) {
            _remainingMs.value = null
            return
        }
        val endAt = System.currentTimeMillis() + minutes * 60_000L
        job = scope.launch {
            while (isActive) {
                val left = endAt - System.currentTimeMillis()
                if (left <= 0) break
                _remainingMs.value = left
                delay(minOf(left, 1000L))
            }
            _minutes.value = 0
            _remainingMs.value = null
            _expired.trySend(Unit)
        }
    }

    companion object {
        val OPTIONS = listOf(0, 15, 30, 60, 90, 120)
        /** The warning dialog appears this long before the end. */
        const val WARNING_MS = 60_000L
    }
}
