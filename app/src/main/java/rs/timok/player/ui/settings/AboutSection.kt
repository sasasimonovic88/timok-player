package rs.timok.player.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import rs.timok.player.BuildConfig
import rs.timok.player.R
import rs.timok.player.ui.components.TvClickable
import rs.timok.player.ui.theme.Brand

/**
 * About: what the app is, that it delivers no content, version from BuildConfig, the Telegram
 * support QR code and the FFmpeg notice the LGPL requires. Everything else about licenses sits
 * behind the "Licenses" button.
 */
@Composable
fun AboutSection(focus: FocusRequester, updateVm: rs.timok.player.ui.update.UpdateViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    var showLicenses by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }
    val updates = updateVm.updates
    val updateState by updates.state.collectAsStateWithLifecycle()
    // Scrolls if a larger display size pushes the text off screen; the scroll container also lets the
    // initial focus request reach the button the same way as on the other settings pages.
    Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(
            Modifier.weight(1f).widthIn(max = 680.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(64.dp))
                Column(Modifier.padding(start = 18.dp)) {
                    Text(stringResource(R.string.app_name), fontSize = 28.sp, color = Brand.TextPrimary)
                    Text(
                        stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        fontSize = 18.sp,
                        color = Brand.Accent,
                    )
                }
            }
            Body(stringResource(R.string.about_description), Brand.TextPrimary)
            Body(stringResource(R.string.about_disclaimer), Brand.TextPrimary)
            Body(stringResource(R.string.about_privacy), Brand.TextSecondary)
            Text(stringResource(R.string.about_copyright), fontSize = 16.sp, color = Brand.TextSecondary)
            Text(stringResource(R.string.about_ffmpeg), fontSize = 14.sp, color = Brand.TextSecondary, lineHeight = 19.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // White margin around the code helps phone cameras lock on from the couch.
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(6.dp)) {
                Image(painterResource(R.drawable.qr_telegram), contentDescription = null, modifier = Modifier.size(190.dp))
            }
            Text(stringResource(R.string.about_telegram), fontSize = 18.sp, color = Brand.TextPrimary)
            Text(stringResource(R.string.about_telegram_link), fontSize = 16.sp, color = Brand.Accent)
            Spacer(Modifier.size(8.dp))
            // The first button on the page gets focus when it opens: updates (sideload build), else licenses.
            if (updates.enabled) {
                AboutButton(
                    text = stringResource(R.string.update_check),
                    onClick = { updates.check(userAsked = true) },
                    modifier = Modifier.focusRequester(focus),
                )
                val status = when (updateState) {
                    rs.timok.player.data.update.UpdateState.Checking -> stringResource(R.string.update_checking)
                    rs.timok.player.data.update.UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                    else -> null
                }
                // Keeps its height while empty, so the licenses button does not jump.
                Text(status.orEmpty(), fontSize = 14.sp, color = Brand.TextSecondary)
            }
            AboutButton(
                text = stringResource(R.string.about_licenses),
                onClick = { showLicenses = true },
                modifier = if (updates.enabled) Modifier else Modifier.focusRequester(focus),
            )
            val errorCount by rs.timok.player.data.log.ErrorLog.count.collectAsStateWithLifecycle()
            AboutButton(
                text = stringResource(R.string.error_log_button, errorCount),
                onClick = { showErrors = true },
            )
        }
    }
    if (showLicenses) LicensesDialog(onDismiss = { showLicenses = false })
    if (showErrors) ErrorLogDialog(onDismiss = { showErrors = false })
}

/**
 * Button of this page. Tracks focus itself: the first one gets focus programmatically when the page opens,
 * which the stock button does not always show.
 */
@Composable
private fun AboutButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    TvClickable(
        onClick = onClick,
        focusedScale = 1.06f,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = if (focused) Color.Black else Color.White,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .width(BUTTON_WIDTH)
                .background(if (focused) Brand.Accent else Brand.Primary, RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

/** Both buttons as wide as the QR code above them. */
private val BUTTON_WIDTH = 220.dp

@Composable
private fun Body(text: String, color: Color) {
    Text(text, fontSize = 18.sp, lineHeight = 25.sp, color = color)
}

/** Library list and full license texts from assets; D-pad up/down scrolls a page at a time, Back closes. */
@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val animate = rs.timok.player.ui.theme.LocalAnimations.current
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.IO) {
            LICENSE_FILES.joinToString("\n\n────────────────────────\n\n") { name ->
                runCatching { context.assets.open("licenses/$name").bufferedReader().use { it.readText().trim() } }.getOrDefault("")
            }
        }
    }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val boxFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(860.dp)
                .fillMaxHeight(0.9f)
                .background(Brand.Background, RoundedCornerShape(20.dp))
                .padding(28.dp),
        ) {
            Text(stringResource(R.string.about_licenses), style = MaterialTheme.typography.titleLarge, color = Brand.TextPrimary)
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
                            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> 1
                            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> -1
                            else -> return@onKeyEvent false
                        }
                        scope.launch { if (animate) scroll.animateScrollBy(step * PAGE_PX) else scroll.scrollBy(step * PAGE_PX) }
                        true
                    }
                    .focusable()
                    .verticalScroll(scroll)
                    .padding(20.dp),
            ) {
                Text(text, fontSize = 15.sp, lineHeight = 21.sp, color = Brand.TextSecondary)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { boxFocus.requestFocus() } }
}

private val LICENSE_FILES = listOf("NOTICE.txt", "LGPL-2.1.txt", "Apache-2.0.txt", "BSD-3-Clause.txt")
private const val PAGE_PX = 360f
