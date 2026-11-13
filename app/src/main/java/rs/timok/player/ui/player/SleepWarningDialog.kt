package rs.timok.player.ui.player

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import rs.timok.player.R
import rs.timok.player.ui.components.TvButton
import rs.timok.player.ui.theme.Brand

/** Last-minute warning of the sleep timer. Extend is focused, so any OK (or Back) keeps watching. */
@Composable
internal fun SleepWarningDialog(secondsLeft: Int, minutes: Int, onExtend: () -> Unit, onSleepNow: () -> Unit) {
    val extendFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onExtend, properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
            Text(
                stringResource(R.string.sleep_warning, secondsLeft),
                style = MaterialTheme.typography.titleLarge,
                color = Brand.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton(stringResource(R.string.sleep_extend, minutes), onExtend, Modifier.focusRequester(extendFocus))
                TvButton(stringResource(R.string.sleep_now), onSleepNow)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { extendFocus.requestFocus() } }
}
