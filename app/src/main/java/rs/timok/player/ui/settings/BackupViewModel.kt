package rs.timok.player.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import rs.timok.player.R
import rs.timok.player.data.backup.BackupException
import rs.timok.player.data.backup.BackupFile
import rs.timok.player.data.backup.BackupRepository
import rs.timok.player.data.settings.AppPrefs
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backup: BackupRepository,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    /** Result of the last export or restore, shown under the buttons. */
    val message: StateFlow<String?> = _message

    private val _pending = MutableStateFlow<BackupFile?>(null)
    /** A read backup waiting for the viewer to confirm the restore. */
    val pending: StateFlow<BackupFile?> = _pending

    private val _languageChanged = MutableStateFlow(false)
    /** Set after a restore that changed the UI language; the screen recreates the activity. */
    val languageChanged: StateFlow<Boolean> = _languageChanged

    /** Suggested file name for the system "save as" dialog. */
    fun suggestedName(): String = "TIMOK-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(Date()) + ".json"

    fun export(uri: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val count = backup.export(uri)
                context.getString(R.string.backup_saved, count)
            } catch (e: Exception) {
                errorText(e)
            }
        }
    }

    fun pick(uri: Uri) {
        viewModelScope.launch {
            try {
                _pending.value = backup.read(uri)
                _message.value = null
            } catch (e: Exception) {
                _message.value = errorText(e)
            }
        }
    }

    fun cancelRestore() {
        _pending.value = null
    }

    fun confirmRestore() {
        val file = _pending.value ?: return
        _pending.value = null
        viewModelScope.launch {
            val languageBefore = AppPrefs.language(context)
            _message.value = try {
                val r = backup.restore(file)
                if (AppPrefs.language(context) != languageBefore) _languageChanged.value = true
                buildString {
                    append(context.getString(R.string.backup_restored, r.favorites, r.watchlist, r.progress))
                    if (r.favoritesMissing > 0) append("\n").append(context.getString(R.string.backup_missing, r.favoritesMissing))
                }
            } catch (e: Exception) {
                errorText(e)
            }
        }
    }

    fun onLanguageApplied() {
        _languageChanged.value = false
    }

    /** "Saved on <date>, version x" line for the confirmation dialog. */
    fun describe(file: BackupFile): String = context.getString(
        R.string.backup_confirm_message,
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(file.exportedAt)),
        file.favorites.size,
        file.watchlist.size,
    )

    private fun errorText(e: Exception): String = when ((e as? BackupException)?.message) {
        "no account" -> context.getString(R.string.backup_error_no_account)
        "not a backup" -> context.getString(R.string.backup_error_invalid)
        "newer format" -> context.getString(R.string.backup_error_newer)
        else -> context.getString(R.string.backup_error_io)
    }
}
