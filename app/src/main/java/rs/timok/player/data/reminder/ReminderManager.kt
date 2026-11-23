package rs.timok.player.data.reminder

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A programme the viewer wants to be told about shortly before it starts. */
@Serializable
data class Reminder(
    val streamId: Long,
    /** Group the channel was chosen in, so watching it keeps zapping there. */
    val groupKey: String,
    val channelName: String,
    val title: String,
    val startMs: Long,
) {
    val key: String get() = "$streamId@$startMs"
}

/**
 * Programme reminders set from the guide. They fire while the app is open (TV apps usually are while watching):
 * [LEAD_MS] before the start, the reminder moves to [due] and the app shows a prompt to switch to the channel.
 */
@Singleton
class ReminderManager @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _reminders = MutableStateFlow(load())
    val reminders: StateFlow<List<Reminder>> = _reminders

    private val _due = MutableStateFlow<Reminder?>(null)
    /** Reminder to show now; cleared with [dismissDue]. */
    val due: StateFlow<Reminder?> = _due

    init {
        scope.launch {
            while (isActive) {
                check()
                delay(TICK_MS)
            }
        }
    }

    fun isSet(streamId: Long, startMs: Long): Boolean = _reminders.value.any { it.streamId == streamId && it.startMs == startMs }

    /** Adds or removes the reminder; returns true when it is now set. */
    fun toggle(reminder: Reminder): Boolean {
        val exists = _reminders.value.any { it.key == reminder.key }
        save(if (exists) _reminders.value.filter { it.key != reminder.key } else (_reminders.value + reminder).sortedBy { it.startMs })
        return !exists
    }

    /** Reminders point at channels of one account; another account becoming active drops them. */
    fun clearAll() {
        _due.value = null
        save(emptyList())
    }

    fun dismissDue() {
        _due.value = null
    }

    private fun check() {
        val now = System.currentTimeMillis()
        val list = _reminders.value
        // Programmes that started long ago (app was closed) are dropped silently.
        val fresh = list.filter { it.startMs > now - STALE_MS }
        val firing = fresh.firstOrNull { it.startMs - LEAD_MS <= now }
        if (firing != null && _due.value == null) {
            _due.value = firing
            save(fresh - firing)
        } else if (fresh.size != list.size) {
            save(fresh)
        }
    }

    private fun load(): List<Reminder> = runCatching {
        prefs.getString(KEY, null)?.let { json.decodeFromString(ListSerializer(Reminder.serializer()), it) }
    }.getOrNull().orEmpty()

    private fun save(list: List<Reminder>) {
        _reminders.value = list
        prefs.edit().putString(KEY, json.encodeToString(ListSerializer(Reminder.serializer()), list)).apply()
    }

    companion object {
        private const val KEY = "list"
        /** How long before the start the prompt appears. */
        const val LEAD_MS = 2 * 60 * 1000L
        private const val STALE_MS = 10 * 60 * 1000L
        private const val TICK_MS = 15_000L
    }
}
