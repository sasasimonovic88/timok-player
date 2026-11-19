package rs.timok.player.data.log

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rs.timok.player.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local error log for support: crashes and playback failures, kept only on the device (no third-party crash
 * reporting). The viewer can read it under About and save it to a file to send. Everything written here passes
 * through [sanitize], so account credentials never end up in it.
 */
object ErrorLog {
    private const val FILE = "error_log.txt"
    private const val SEPARATOR = "\n────────\n"
    private const val MAX_ENTRIES = 30
    private const val MAX_STACK_LINES = 25

    private lateinit var file: File
    private val _count = MutableStateFlow(0)
    /** Number of stored entries, for the About page button. */
    val count: StateFlow<Int> = _count

    /** Called first thing in Application.onCreate: records crashes before the process dies. */
    fun init(context: Context) {
        file = File(context.filesDir, FILE)
        _count.value = entries().size
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { add("CRASH", "${error.javaClass.name}: ${error.message}", stackOf(error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A playback or other non-fatal problem worth showing to support. */
    fun event(kind: String, message: String) {
        runCatching { add(kind, message, null) }
    }

    /** Newest first. */
    fun entries(): List<String> {
        if (!::file.isInitialized || !file.exists()) return emptyList()
        return file.readText().split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.reversed()
    }

    /** The whole log as one text, with a header describing the device (for saving to a file). */
    fun export(): String = buildString {
        append("TIM-OK Player ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE)
        append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n")
        append(entries().joinToString("\n\n"))
    }

    fun clear() {
        if (::file.isInitialized) file.delete()
        _count.value = 0
    }

    @Synchronized
    private fun add(kind: String, message: String, stack: String?) {
        if (!::file.isInitialized) return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val entry = buildString {
            append(time).append("  ").append(kind).append("  v").append(BuildConfig.VERSION_NAME).append('\n')
            append(sanitize(message))
            if (stack != null) append('\n').append(sanitize(stack))
        }
        val kept = (file.takeIf { it.exists() }?.readText()?.split(SEPARATOR)?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()).takeLast(MAX_ENTRIES - 1) + entry
        file.writeText(kept.joinToString(SEPARATOR))
        _count.value = kept.size
    }

    private fun stackOf(error: Throwable): String {
        val lines = error.stackTraceToString().lines()
        return lines.take(MAX_STACK_LINES).joinToString("\n") + if (lines.size > MAX_STACK_LINES) "\n…" else ""
    }

    /** Strips account credentials from stream URLs and API query strings. */
    fun sanitize(text: String): String = text
        .replace(Regex("/(live|movie|series|timeshift)/[^/\\s]+/[^/\\s]+/"), "/$1/***/***/")
        .replace(Regex("(username|password)=[^&\\s]*"), "$1=***")
}
