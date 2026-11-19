package rs.timok.player.ui.program

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import rs.timok.player.R
import rs.timok.player.data.db.ProgramEntity
import rs.timok.player.ui.components.Clock
import rs.timok.player.ui.components.ProgramProgress
import rs.timok.player.ui.components.TvClickable
import rs.timok.player.ui.theme.Brand
import java.text.DateFormat
import java.util.Date

private val scrim = Brush.horizontalGradient(
    0f to Brand.Background.copy(alpha = 0.97f),
    0.7f to Brand.Background.copy(alpha = 0.90f),
    1f to Color.Transparent,
)

/**
 * Full guide of one channel, grouped by day, starting on the programme that is on now; the live channel keeps
 * playing on the right. OK watches the channel.
 */
@Composable
fun ProgramScreen(
    onWatch: (group: String, streamId: Long) -> Unit,
    onPlayArchive: (rs.timok.player.data.repo.VodPlayRequest) -> Unit,
    onBack: () -> Unit,
    vm: ProgramViewModel = hiltViewModel(),
) {
    val channel by vm.channel.collectAsStateWithLifecycle()
    val schedule by vm.schedule.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val reminderStarts by vm.reminderStarts.collectAsStateWithLifecycle()
    val reminderSetText = stringResource(R.string.reminder_set_short)
    val reminderRemovedText = stringResource(R.string.reminder_removed)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val notInArchive = stringResource(R.string.program_not_in_archive)
    BackHandler(onBack = onBack)

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(820.dp)
                .fillMaxHeight()
                .background(scrim)
                .padding(start = 48.dp, end = 64.dp, top = 28.dp, bottom = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.program_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Brand.Accent,
                    modifier = Modifier.weight(1f),
                )
                Clock(color = Brand.TextSecondary)
            }
            Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                channel?.logo?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(width = 64.dp, height = 40.dp).padding(end = 12.dp),
                    )
                }
                Text(
                    listOfNotNull(channel?.number?.toString(), channel?.name).joinToString("  "),
                    style = MaterialTheme.typography.titleMedium,
                    color = Brand.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (val s = schedule) {
                ScheduleState.Loading -> Hint(stringResource(R.string.program_loading))
                is ScheduleState.Ready -> if (s.programs.isEmpty()) {
                    Hint(stringResource(R.string.program_empty))
                } else {
                    val hasArchive = channel?.hasArchive == true
                    Schedule(s.programs, now, hasArchive, reminderStarts) { program ->
                        // A programme that already ran plays from the archive (channels that have one); else live.
                        if (hasArchive && program.endMs <= System.currentTimeMillis()) {
                            scope.launch {
                                val request = vm.archiveRequest(program)
                                if (request != null) onPlayArchive(request)
                                else android.widget.Toast.makeText(context, notInArchive, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } else if (program.startMs > System.currentTimeMillis()) {
                            // A programme that has not started yet: OK sets or removes a reminder.
                            val set = vm.toggleReminder(program)
                            android.widget.Toast.makeText(context, if (set) reminderSetText else reminderRemovedText, android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            onWatch(vm.groupKey, vm.streamId)
                        }
                    }
                }
            }
        }
    }
}

private sealed interface ScheduleRow {
    data class Day(val label: String) : ScheduleRow
    data class Item(val program: ProgramEntity) : ScheduleRow
}

@Composable
private fun Schedule(programs: List<ProgramEntity>, now: Long, hasArchive: Boolean, reminderStarts: Set<Long>, onClick: (ProgramEntity) -> Unit) {
    val context = LocalContext.current
    val today = stringResource(R.string.archive_today)
    val tomorrow = stringResource(R.string.program_tomorrow)
    val yesterday = stringResource(R.string.archive_yesterday)
    val rows = remember(programs, today, tomorrow, yesterday) {
        buildList {
            var lastDay: String? = null
            for (p in programs) {
                val day = when {
                    DateUtils.isToday(p.startMs) -> today
                    DateUtils.isToday(p.startMs - DateUtils.DAY_IN_MILLIS) -> tomorrow
                    DateUtils.isToday(p.startMs + DateUtils.DAY_IN_MILLIS) -> yesterday
                    else -> DateUtils.formatDateTime(context, p.startMs, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR)
                        .replaceFirstChar { it.titlecase() }
                }
                if (day != lastDay) {
                    add(ScheduleRow.Day(day))
                    lastDay = day
                }
                add(ScheduleRow.Item(p))
            }
        }
    }
    // Start on what is on now (or the next programme when there is a gap).
    val startIndex = remember(rows) {
        val nowMs = System.currentTimeMillis()
        rows.indexOfFirst { it is ScheduleRow.Item && it.program.endMs > nowMs }
            .takeIf { it >= 0 } ?: rows.indexOfFirst { it is ScheduleRow.Item }
    }
    val listState = rememberLazyListState()
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(rows) {
        // One row of context above, so the day header of the current programme stays visible.
        listState.scrollToItem((startIndex - 1).coerceAtLeast(0))
        withFrameNanos { }
        runCatching { startFocus.requestFocus() }
    }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp),
    ) {
        items(rows.size, key = { i -> rows[i].let { if (it is ScheduleRow.Item) "p${it.program.id}" else "d$i" } }) { i ->
            when (val row = rows[i]) {
                is ScheduleRow.Day -> Text(
                    row.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.Accent,
                    modifier = Modifier.padding(start = 12.dp, top = if (i == 0) 0.dp else 14.dp, bottom = 4.dp),
                )
                is ScheduleRow.Item -> ProgramRow(
                    program = row.program,
                    now = now,
                    timeFormat = timeFormat,
                    archive = hasArchive,
                    reminded = row.program.startMs in reminderStarts,
                    onClick = { onClick(row.program) },
                    modifier = if (i == startIndex) Modifier.focusRequester(startFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun ProgramRow(program: ProgramEntity, now: Long, timeFormat: DateFormat, archive: Boolean, reminded: Boolean, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val isNow = program.startMs <= now && program.endMs > now
    val isPast = program.endMs <= now
    val dim = if (isPast) 0.5f else 1f
    TvClickable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                "${timeFormat.format(Date(program.startMs))}–${timeFormat.format(Date(program.endMs))}",
                fontSize = 16.sp,
                color = (if (isNow) Brand.Accent else Brand.TextSecondary).copy(alpha = dim),
                modifier = Modifier.width(130.dp),
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isNow) {
                        Text(
                            stringResource(R.string.program_now),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .background(Brand.Accent, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    if (isPast && archive) {
                        // Marks programmes that can be watched from the archive.
                        Text("⟲ ", fontSize = 17.sp, color = Brand.Accent)
                    }
                    if (reminded) Text("🔔 ", fontSize = 15.sp)
                    Text(
                        program.title,
                        fontSize = 17.sp,
                        fontWeight = if (isNow) FontWeight.SemiBold else FontWeight.Normal,
                        color = Brand.TextPrimary.copy(alpha = dim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isNow) {
                    val progress = ((now - program.startMs).toFloat() / (program.endMs - program.startMs)).coerceIn(0f, 1f)
                    ProgramProgress(progress, Modifier.fillMaxWidth(0.6f).padding(top = 6.dp, bottom = 2.dp))
                }
                program.description?.takeIf { it.isNotBlank() }?.let {
                    // The focused programme shows its whole description; the others one line.
                    Text(
                        it,
                        fontSize = 13.sp,
                        color = Brand.TextSecondary.copy(alpha = dim),
                        maxLines = if (focused) 6 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.padding(top = 16.dp, start = 4.dp)) { Text(text, color = Brand.TextSecondary) }
}
