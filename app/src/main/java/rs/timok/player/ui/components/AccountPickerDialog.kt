package rs.timok.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import rs.timok.player.R
import rs.timok.player.data.db.PlaylistEntity
import rs.timok.player.ui.theme.Brand

/** Quick account switch from the left menu: the stored accounts, focus on the active one, Back closes. */
@Composable
fun AccountPickerDialog(
    accounts: List<PlaylistEntity>,
    activeId: Long?,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val activeFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(560.dp)
                .background(Brand.Background, RoundedCornerShape(16.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.account_picker_title),
                style = MaterialTheme.typography.titleLarge,
                color = Brand.TextPrimary,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
            accounts.forEach { a ->
                val active = a.id == activeId
                TvClickable(
                    onClick = { if (active) onDismiss() else onPick(a.id) },
                    selected = active,
                    modifier = Modifier.fillMaxWidth().then(if (active) Modifier.focusRequester(activeFocus) else Modifier),
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (active) "●" else "○",
                            color = if (active) Brand.Accent else Brand.TextSecondary,
                            modifier = Modifier.width(32.dp),
                        )
                        Column {
                            Text(
                                a.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (active) Brand.Accent else Brand.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(a.username, style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { activeFocus.requestFocus() } }
}
