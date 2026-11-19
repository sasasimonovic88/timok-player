package rs.timok.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import rs.timok.player.R
import rs.timok.player.data.reminder.Reminder
import rs.timok.player.ui.components.TvButton
import rs.timok.player.ui.player.glassHighlight
import rs.timok.player.ui.player.glassTint
import rs.timok.player.ui.player.panelShape
import rs.timok.player.ui.theme.Brand
import java.text.DateFormat
import java.util.Date

/** "Starting soon" prompt for a programme reminder, above any screen; closes by itself after a minute. */
@Composable
fun ReminderPopup(reminder: Reminder, onWatch: () -> Unit, onDismiss: () -> Unit) {
    val watchFocus = remember { FocusRequester() }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    LaunchedEffect(reminder.key) {
        delay(AUTO_CLOSE_MS)
        onDismiss()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(560.dp)
                .clip(panelShape)
                .background(Brand.Background)
                .background(glassTint)
                .background(glassHighlight)
                .border(1.dp, Color.White.copy(alpha = 0.14f), panelShape)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(56.dp))
            Text(
                stringResource(R.string.reminder_starts, timeFormat.format(Date(reminder.startMs))),
                color = Brand.Accent,
                textAlign = TextAlign.Center,
            )
            Text(
                reminder.title,
                style = MaterialTheme.typography.titleLarge,
                color = Brand.TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(reminder.channelName, color = Brand.TextSecondary, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
                TvButton(stringResource(R.string.reminder_watch), onWatch, Modifier.focusRequester(watchFocus))
                TvButton(stringResource(R.string.error_log_close), onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { watchFocus.requestFocus() } }
}

private const val AUTO_CLOSE_MS = 60_000L
