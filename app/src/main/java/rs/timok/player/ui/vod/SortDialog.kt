package rs.timok.player.ui.vod

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import rs.timok.player.R
import rs.timok.player.domain.VodSort
import rs.timok.player.ui.components.TvClickable
import rs.timok.player.ui.theme.Brand

private val dialogShape = RoundedCornerShape(20.dp)

/** List of orders for the movie/series grid; opens on the current one, OK picks, Back closes. */
@Composable
fun SortDialog(current: VodSort, onPick: (VodSort) -> Unit, onDismiss: () -> Unit) {
    val currentFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(480.dp)
                .background(Brand.Background, dialogShape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.vod_sort_title),
                style = MaterialTheme.typography.titleLarge,
                color = Brand.TextPrimary,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
            VodSort.entries.forEach { sort ->
                val selected = sort == current
                TvClickable(
                    onClick = { onPick(sort) },
                    selected = selected,
                    modifier = Modifier.fillMaxWidth().then(if (selected) Modifier.focusRequester(currentFocus) else Modifier),
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (selected) "●" else "○",
                            color = if (selected) Brand.Accent else Brand.TextSecondary,
                            modifier = Modifier.width(32.dp),
                        )
                        Text(
                            stringResource(sortLabel(sort)),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) Brand.Accent else Brand.TextPrimary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { currentFocus.requestFocus() } }
}

fun sortLabel(sort: VodSort) = when (sort) {
    VodSort.DEFAULT -> R.string.vod_sort_default
    VodSort.ADDED -> R.string.vod_sort_added
    VodSort.RATING -> R.string.vod_sort_rating
    VodSort.YEAR -> R.string.vod_sort_year
    VodSort.NAME -> R.string.vod_sort_name
}
