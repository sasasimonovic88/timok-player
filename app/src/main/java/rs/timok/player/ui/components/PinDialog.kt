package rs.timok.player.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import rs.timok.player.R
import rs.timok.player.data.parental.PinCheck
import rs.timok.player.data.parental.ParentalControl
import rs.timok.player.ui.player.glassHighlight
import rs.timok.player.ui.player.glassTint
import rs.timok.player.ui.player.panelShape
import rs.timok.player.ui.theme.Brand

/**
 * Asks for a 4-digit PIN: digit keys on the remote, or the on-screen keypad for remotes without them.
 * [onPin] gets the full PIN and returns an error to show (the digits are cleared), or null when it was accepted;
 * the caller then closes the dialog or moves it to its next step (new [title]).
 */
@Composable
fun PinDialog(
    title: String,
    onPin: (String) -> String?,
    onDismiss: () -> Unit,
    message: String? = null,
) {
    var digits by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val firstKey = remember { FocusRequester() }

    fun type(d: Char) {
        if (digits.length >= ParentalControl.PIN_LENGTH) return
        digits += d
        if (digits.length == ParentalControl.PIN_LENGTH) {
            val pin = digits
            digits = ""
            error = onPin(pin)
        } else {
            error = null
        }
    }

    fun erase() {
        digits = digits.dropLast(1)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(440.dp)
                .clip(panelShape)
                .background(Brand.Background)
                .background(glassTint)
                .background(glassHighlight)
                .border(1.dp, Color.White.copy(alpha = 0.14f), panelShape)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (val code = e.nativeKeyEvent.keyCode) {
                        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                            type('0' + (code - KeyEvent.KEYCODE_0)); true
                        }
                        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                            type('0' + (code - KeyEvent.KEYCODE_NUMPAD_0)); true
                        }
                        KeyEvent.KEYCODE_DEL -> {
                            erase(); true
                        }
                        else -> false
                    }
                }
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(48.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = Brand.TextPrimary, textAlign = TextAlign.Center)
            message?.let { Text(it, color = Brand.TextSecondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                repeat(ParentalControl.PIN_LENGTH) { i ->
                    val filled = i < digits.length
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(if (filled) Brand.Accent else Color.Transparent)
                            .border(2.dp, if (filled) Brand.Accent else Brand.TextSecondary, CircleShape),
                    )
                }
            }
            // Fixed height, so the keypad does not jump when an error appears.
            Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
                Text(
                    error.orEmpty(),
                    color = Brand.Error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
            val rows = listOf("123", "456", "789", "<0x")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { c, key ->
                            val label = when (key) {
                                '<' -> "⌫"
                                'x' -> stringResource(R.string.exit_cancel)
                                else -> key.toString()
                            }
                            TvClickable(
                                onClick = {
                                    when (key) {
                                        '<' -> erase()
                                        'x' -> onDismiss()
                                        else -> type(key)
                                    }
                                },
                                focusedScale = 1.08f,
                                modifier = Modifier
                                    .size(width = 96.dp, height = 56.dp)
                                    .background(Brand.Surface.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                    .then(if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier),
                            ) {
                                Box(Modifier.size(width = 96.dp, height = 56.dp), contentAlignment = Alignment.Center) {
                                    Text(label, fontSize = if (key == 'x') 16.sp else 24.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }
}

/** The app-wide prompt for [ParentalControl.requestUnlock]. */
@Composable
fun ParentalPinPrompt(parental: ParentalControl) {
    val wrong = stringResource(R.string.pin_wrong)
    val waitFormat = stringResource(R.string.pin_wait)
    PinDialog(
        title = stringResource(R.string.pin_enter_title),
        message = stringResource(R.string.pin_enter_message),
        onPin = { pin ->
            when (val r = parental.submit(pin)) {
                PinCheck.Ok -> null
                PinCheck.Wrong -> wrong
                is PinCheck.Wait -> String.format(waitFormat, r.seconds)
            }
        },
        onDismiss = parental::cancelRequest,
    )
}
