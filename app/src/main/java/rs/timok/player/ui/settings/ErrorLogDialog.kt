package rs.timok.player.ui.settings

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rs.timok.player.R
import rs.timok.player.data.log.ErrorLog
import rs.timok.player.ui.components.TvButton
import rs.timok.player.ui.theme.Brand
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shows the local error log (newest first); it can be saved to a file for support or cleared. */
@Composable
internal fun ErrorLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var empty by remember { mutableStateOf(false) }
    fun reload() {
        val entries = ErrorLog.entries()
        empty = entries.isEmpty()
        text = if (empty) context.getString(R.string.error_log_empty) else entries.joinToString("\n\n")
    }
    LaunchedEffect(Unit) { reload() }
    val saved = stringResource(R.string.error_log_saved)
    val failed = stringResource(R.string.backup_error_io)
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(ErrorLog.export().toByteArray()) } != null }
                    .getOrDefault(false)
            }
            Toast.makeText(context, if (ok) saved else failed, Toast.LENGTH_LONG).show()
        }
    }
    val scroll = rememberScrollState()
    val boxFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(900.dp)
                .fillMaxHeight(0.9f)
                .background(Brand.Background, RoundedCornerShape(20.dp))
                .padding(28.dp),
        ) {
            Text(stringResource(R.string.error_log_title), style = MaterialTheme.typography.titleLarge, color = Brand.TextPrimary)
            Text(stringResource(R.string.error_log_hint), fontSize = 14.sp, color = Brand.TextSecondary, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.size(14.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(if (focused) Brand.Surface else Brand.Surface.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .focusRequester(boxFocus)
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val step = when (e.nativeKeyEvent.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN -> 1
                            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_PAGE_UP -> -1
                            else -> return@onKeyEvent false
                        }
                        // At the bottom, Down moves on to the buttons.
                        if (step > 0 && scroll.value >= scroll.maxValue) return@onKeyEvent false
                        scope.launch { scroll.scrollBy(step * 360f) }
                        true
                    }
                    .focusable()
                    .verticalScroll(scroll)
                    .padding(20.dp),
            ) {
                Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = Brand.TextSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 16.dp)) {
                TvButton(
                    stringResource(R.string.error_log_save),
                    onClick = {
                        val name = "TIMOK-greske-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + ".txt"
                        try {
                            saveLauncher.launch(name)
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, R.string.backup_no_picker, Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = !empty,
                )
                TvButton(
                    stringResource(R.string.error_log_clear),
                    onClick = {
                        ErrorLog.clear()
                        reload()
                    },
                    enabled = !empty,
                )
                TvButton(stringResource(R.string.error_log_close), onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { boxFocus.requestFocus() } }
}
