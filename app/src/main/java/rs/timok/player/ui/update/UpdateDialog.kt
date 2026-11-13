package rs.timok.player.ui.update

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import rs.timok.player.BuildConfig
import rs.timok.player.R
import rs.timok.player.data.update.UpdateManager
import rs.timok.player.data.update.UpdateState
import rs.timok.player.ui.components.ProgramProgress
import rs.timok.player.ui.components.TvButton
import rs.timok.player.ui.player.glassHighlight
import rs.timok.player.ui.player.glassTint
import rs.timok.player.ui.player.panelShape
import rs.timok.player.ui.theme.Brand

/**
 * Offer of a newer version, its download and the steps around the system installer. Shown above any screen;
 * nothing is shown while there is nothing to offer (quiet daily check, or up to date).
 */
@Composable
fun UpdateDialog(updates: UpdateManager) {
    val state by updates.state.collectAsStateWithLifecycle()
    // Coming back from the "install unknown apps" setting: continue the install if it is allowed now.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updates.onReturnFromPermission() }

    when (val s = state) {
        is UpdateState.Available -> Panel(
            title = stringResource(R.string.update_available, s.info.versionName),
            subtitle = stringResource(R.string.update_current, BuildConfig.VERSION_NAME),
            body = s.info.changelog,
            primary = stringResource(R.string.update_install) to { updates.install(s.info) },
            secondary = stringResource(R.string.update_later) to updates::dismiss,
            onDismiss = updates::dismiss,
        )
        is UpdateState.Downloading -> Panel(
            title = stringResource(R.string.update_downloading, s.info.versionName),
            subtitle = "${(s.progress * 100).toInt()}%",
            progress = s.progress,
            secondary = stringResource(R.string.update_cancel) to updates::dismiss,
            onDismiss = {},
        )
        is UpdateState.NeedsPermission -> Panel(
            title = stringResource(R.string.update_permission_title),
            body = stringResource(R.string.update_permission_body),
            primary = stringResource(R.string.update_retry) to { updates.install(s.info) },
            secondary = stringResource(R.string.update_later) to updates::dismiss,
            onDismiss = updates::dismiss,
        )
        is UpdateState.Failed -> Panel(
            title = stringResource(R.string.update_failed_title),
            body = stringResource(
                when (s.reason) {
                    UpdateState.Reason.NETWORK -> R.string.update_failed_network
                    UpdateState.Reason.CHECKSUM -> R.string.update_failed_checksum
                    UpdateState.Reason.NO_INSTALLER -> R.string.update_failed_installer
                }
            ),
            secondary = stringResource(R.string.update_ok) to updates::dismiss,
            onDismiss = updates::dismiss,
        )
        else -> Unit
    }
}

@Composable
private fun Panel(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    body: String? = null,
    progress: Float? = null,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
) {
    val firstFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(600.dp)
                .clip(panelShape)
                .background(Brand.Background)
                .background(glassTint)
                .background(glassHighlight)
                .border(1.dp, Color.White.copy(alpha = 0.14f), panelShape)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(56.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = Brand.TextPrimary, textAlign = TextAlign.Center)
            subtitle?.let { Text(it, color = Brand.Accent, textAlign = TextAlign.Center) }
            progress?.let { ProgramProgress(it, Modifier.fillMaxWidth(0.8f)) }
            body?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    color = Brand.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
                // The main action gets focus: one OK installs, Back (or the second button) postpones.
                primary?.let { (label, action) -> TvButton(label, action, Modifier.focusRequester(firstFocus)) }
                secondary?.let { (label, action) ->
                    TvButton(label, action, if (primary == null) Modifier.focusRequester(firstFocus) else Modifier)
                }
            }
        }
    }
    LaunchedEffect(title) { runCatching { firstFocus.requestFocus() } }
}
