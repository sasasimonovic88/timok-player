package rs.timok.player.data.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import rs.timok.player.BuildConfig
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Description of the newest release, published as `update.json` next to the APK in the GitHub release.
 * [UPDATE_URL][BuildConfig.UPDATE_URL] points at the "latest release" download link, so it never changes.
 */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    /** SHA-256 of the APK (hex); a download that does not match is never installed. */
    val sha256: String,
    /** Serbian changelog; the only one versions before 0.5.7 read. */
    val changelog: String = "",
    /** Changelog per app language ("en", "de", "sk"...); [changelog] when the language is missing. */
    val changelogs: Map<String, String> = emptyMap(),
) {
    fun changelogFor(language: String): String = changelogs[language]?.takeIf { it.isNotBlank() } ?: changelog
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** Checked on request and nothing newer exists. */
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    /** Android must first allow this app to install apps; the viewer was sent to that setting. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Failed(val reason: Reason) : UpdateState

    enum class Reason { NETWORK, CHECKSUM, NO_INSTALLER }
}

/**
 * Self-update for the sideload build: checks the release description, downloads the APK, verifies it and hands
 * it to the system installer. Android itself refuses an APK signed with another key, so a tampered file cannot
 * replace the app; the checksum also rejects broken or partial downloads.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private var job: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    val enabled: Boolean get() = BuildConfig.SELF_UPDATE && BuildConfig.UPDATE_URL.isNotBlank()

    /** At most once a day, quietly: only an available update is shown. */
    fun checkIfDue() {
        if (!enabled) return
        val last = prefs.getLong(KEY_LAST_CHECK, 0)
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return
        check(userAsked = false)
    }

    /** Checks now; when [userAsked], "up to date" and errors are reported too. */
    fun check(userAsked: Boolean = true) {
        if (!enabled || job?.isActive == true) return
        if (userAsked) _state.value = UpdateState.Checking
        job = scope.launch {
            val info = runCatching { fetchInfo() }
                .onFailure { Log.w(TAG, "Update check failed: $it") }
                .getOrElse {
                    if (userAsked) _state.value = UpdateState.Failed(UpdateState.Reason.NETWORK)
                    return@launch
                }
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            _state.value = when {
                info.versionCode > BuildConfig.VERSION_CODE -> UpdateState.Available(info)
                userAsked -> UpdateState.UpToDate
                else -> UpdateState.Idle
            }
        }
    }

    /** "Later": hide the offer until the next daily check. */
    fun dismiss() {
        job?.cancel()
        _state.value = UpdateState.Idle
    }

    /** Downloads, verifies and opens the installer. */
    fun install(info: UpdateInfo) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Downloading(info, 0f)
            val file = runCatching { download(info) }
                .onFailure { Log.w(TAG, "Update download failed: $it") }
                .getOrElse {
                    _state.value = if (it is ChecksumException) UpdateState.Failed(UpdateState.Reason.CHECKSUM)
                    else UpdateState.Failed(UpdateState.Reason.NETWORK)
                    return@launch
                }
            withContext(Dispatchers.Main) { openInstaller(info, file) }
        }
    }

    private fun fetchInfo(): UpdateInfo {
        val request = Request.Builder().url(BuildConfig.UPDATE_URL).header("Cache-Control", "no-cache").build()
        okHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return json.decodeFromString(UpdateInfo.serializer(), response.body!!.string())
        }
    }

    private suspend fun download(info: UpdateInfo): File {
        val dir = updateDir().apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "TIMOK-Player-${info.versionName}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        okHttp.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body!!
            val total = body.contentLength().takeIf { it > 0 }
            var done = 0L
            var lastReported = 0f
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (!scope.isActive) error("cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total != null) {
                            val p = done.toFloat() / total
                            if (p - lastReported >= 0.02f) {
                                lastReported = p
                                _state.value = UpdateState.Downloading(info, p)
                            }
                        }
                    }
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (!hex.equals(info.sha256.trim(), ignoreCase = true)) {
            file.delete()
            throw ChecksumException()
        }
        return file
    }

    private fun openInstaller(info: UpdateInfo, file: File) {
        // Android 8+: this app needs the "install unknown apps" permission first.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(info)
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(settings)
            } catch (e: ActivityNotFoundException) {
                // Some TV boxes have no such screen; the dialog explains where to allow it.
                Log.w(TAG, "No unknown-sources settings screen")
            }
            return
        }
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        } else {
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
            _state.value = UpdateState.Available(info)
        } catch (e: ActivityNotFoundException) {
            _state.value = UpdateState.Failed(UpdateState.Reason.NO_INSTALLER)
        }
    }

    /** Back from the permission screen: try the install again if it is now allowed. */
    fun onReturnFromPermission() {
        val s = _state.value as? UpdateState.NeedsPermission ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) {
            val file = updateDir().listFiles()?.firstOrNull { it.name.endsWith(".apk") }
            if (file != null) openInstaller(s.info, file) else install(s.info)
        }
    }

    /** Android 6 needs a world-readable location for the installer; newer versions use the FileProvider. */
    private fun updateDir(): File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) File(context.cacheDir, "updates")
        else File(context.getExternalFilesDir(null), "updates")

    private class ChecksumException : Exception("checksum mismatch")

    private companion object {
        const val TAG = "UpdateManager"
        const val KEY_LAST_CHECK = "last_check"
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
