package rs.timok.player.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import rs.timok.player.R
import rs.timok.player.ui.components.ConfirmDialog
import rs.timok.player.ui.theme.Brand

/**
 * Save the viewer's favorites, "My List", resume points and settings to a file, and restore them, e.g. on a new
 * box or after reinstalling. Uses the system file picker (Downloads, USB stick...).
 */
@Composable
internal fun BackupSection(focus: FocusRequester, vm: BackupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val message by vm.message.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val languageChanged by vm.languageChanged.collectAsStateWithLifecycle()
    val noPicker = stringResource(R.string.backup_no_picker)

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::export)
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::pick)
    }

    // A restored UI language only takes effect after the activity is recreated.
    LaunchedEffect(languageChanged) {
        if (languageChanged) {
            vm.onLanguageApplied()
            context.findActivity()?.recreate()
        }
    }

    Column(Modifier.width(760.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingRow(
            title = stringResource(R.string.backup_save),
            hint = stringResource(R.string.backup_save_hint),
            value = "⤓",
            valueActive = true,
            onClick = {
                try {
                    saveLauncher.launch(vm.suggestedName())
                } catch (e: ActivityNotFoundException) {
                    android.widget.Toast.makeText(context, noPicker, android.widget.Toast.LENGTH_LONG).show()
                }
            },
            modifier = Modifier.focusRequester(focus),
        )
        SettingRow(
            title = stringResource(R.string.backup_restore),
            hint = stringResource(R.string.backup_restore_hint),
            value = "⤒",
            valueActive = true,
            onClick = {
                try {
                    // Some pickers do not know the JSON type of files on USB sticks; offer everything.
                    openLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                } catch (e: ActivityNotFoundException) {
                    android.widget.Toast.makeText(context, noPicker, android.widget.Toast.LENGTH_LONG).show()
                }
            },
        )
        message?.let { Text(it, color = Brand.TextPrimary, style = MaterialTheme.typography.bodyLarge) }
        Text(stringResource(R.string.backup_note), color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }

    pending?.let { file ->
        ConfirmDialog(
            title = stringResource(R.string.backup_confirm_title),
            message = vm.describe(file),
            confirmLabel = stringResource(R.string.backup_confirm),
            onConfirm = vm::confirmRestore,
            onDismiss = vm::cancelRestore,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
