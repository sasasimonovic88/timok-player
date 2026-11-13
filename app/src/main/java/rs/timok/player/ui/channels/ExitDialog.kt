package rs.timok.player.ui.channels

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import rs.timok.player.R
import rs.timok.player.ui.components.ConfirmDialog

/** Guards against an accidental Back on the top-level screen. */
@Composable
internal fun ExitDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.exit_title),
        confirmLabel = stringResource(R.string.exit_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
