package rs.timok.player.ui.components

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import rs.timok.player.R
import rs.timok.player.ui.player.glassHighlight
import rs.timok.player.ui.player.glassTint
import rs.timok.player.ui.player.panelShape
import rs.timok.player.ui.theme.Brand

/**
 * Asks before something that cannot be undone. A separate dialog window keeps the focused row
 * behind it intact, so cancelling returns exactly where the user was.
 */
@Composable
fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    cancelLabel: String = stringResource(R.string.exit_cancel),
) {
    // Cancel is focused first: an accidental second OK or Back must not confirm.
    val cancelFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(520.dp)
                .clip(panelShape)
                .background(Brand.Background)
                .background(glassTint)
                .background(glassHighlight)
                .border(1.dp, Color.White.copy(alpha = 0.14f), panelShape)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(64.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = Brand.TextPrimary, textAlign = TextAlign.Center)
            message?.let { Text(it, color = Brand.TextSecondary, textAlign = TextAlign.Center) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton(cancelLabel, onDismiss, Modifier.focusRequester(cancelFocus))
                TvButton(confirmLabel, onConfirm)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
}
