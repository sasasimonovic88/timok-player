package rs.timok.player.ui.archive

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import rs.timok.player.R
import rs.timok.player.data.repo.VodPlayRequest
import rs.timok.player.domain.ArchiveProgram
import rs.timok.player.ui.components.Clock
import rs.timok.player.ui.components.TvButton
import rs.timok.player.ui.components.TvClickable
import rs.timok.player.ui.theme.Brand
import java.text.DateFormat
import java.util.Date

private val scrim = Brush.horizontalGradient(
    0f to Brand.Background.copy(alpha = 0.97f),
    0.7f to Brand.Background.copy(alpha = 0.90f),
    1f to Color.Transparent,
)

/** Past programmes of one channel, grouped by day, newest first; the live channel keeps playing behind. */
@Composable
fun ArchiveScreen(
    onPlay: (VodPlayRequest) -> Unit,
    onBack: () -> Unit,
    vm: ArchiveViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    BackHandler(onBack = onBack)

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(820.dp)
                .fillMaxHeight()
                .background(scrim)
                .padding(start = 48.dp, end = 64.dp, top = 28.dp, bottom = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.archive_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Brand.Accent,
                    modifier = Modifier.weight(1f),
                )
                Clock(color = Brand.TextSecondary)
            }
            Text(
                vm.channelName,
                style = MaterialTheme.typography.titleMedium,
                color = Brand.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            when (val s = state) {
                ArchiveState.Loading -> Hint(stringResource(R.string.archive_loading))
                is ArchiveState.Error -> {
                    Hint(s.message)
                    TvButton(stringResource(R.string.retry), vm::load, Modifier.padding(top = 16.dp).focusRequester(firstFocus))
                    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
                }
                is ArchiveState.Ready -> if (s.programs.isEmpty()) {
                    Hint(stringResource(R.string.archive_empty))
                } else {
                    ProgramList(s.programs, firstFocus) { program ->
                        scope.launch { vm.playRequest(program)?.let(onPlay) }
                    }
                }
            }
        }
    }
}

private sealed interface ArchiveRow {
    data class Day(val label: String) : ArchiveRow
    data class Item(val program: ArchiveProgram) : ArchiveRow
}

@Composable
private fun ProgramList(programs: List<ArchiveProgram>, firstFocus: FocusRequester, onClick: (ArchiveProgram) -> Unit) {
    val context = LocalContext.current
    val today = stringResource(R.string.archive_today)
    val yesterday = stringResource(R.string.archive_yesterday)
    val rows = remember(programs, today, yesterday) {
        buildList {
            var lastDay: String? = null
            for (p in programs) {
                val day = when {
                    DateUtils.isToday(p.startMs) -> today
                    DateUtils.isToday(p.startMs + DateUtils.DAY_IN_MILLIS) -> yesterday
                    else -> DateUtils.formatDateTime(context, p.startMs, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR)
                        .replaceFirstChar { it.titlecase() }
                }
                if (day != lastDay) {
                    add(ArchiveRow.Day(day))
                    lastDay = day
                }
                add(ArchiveRow.Item(p))
            }
        }
    }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    val firstItem = rows.indexOfFirst { it is ArchiveRow.Item }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp),
    ) {
        items(rows.size, key = { i -> rows[i].let { if (it is ArchiveRow.Item) "p${it.program.startMs}" else "d$i" } }) { i ->
            when (val row = rows[i]) {
                is ArchiveRow.Day -> Text(
                    row.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.Accent,
                    modifier = Modifier.padding(start = 12.dp, top = if (i == 0) 0.dp else 14.dp, bottom = 4.dp),
                )
                is ArchiveRow.Item -> {
                    val p = row.program
                    TvClickable(
                        onClick = { onClick(p) },
                        modifier = Modifier.fillMaxWidth().let { if (i == firstItem) it.focusRequester(firstFocus) else it },
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${timeFormat.format(Date(p.startMs))}–${timeFormat.format(Date(p.endMs))}",
                                fontSize = 16.sp,
                                color = Brand.TextSecondary,
                                modifier = Modifier.width(130.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(p.title, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                p.description?.let {
                                    Text(it, fontSize = 13.sp, color = Brand.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.padding(top = 16.dp, start = 4.dp)) { Text(text, color = Brand.TextSecondary) }
}
