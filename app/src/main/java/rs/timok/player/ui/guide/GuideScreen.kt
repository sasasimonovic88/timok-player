package rs.timok.player.ui.guide

import android.text.format.DateUtils
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import rs.timok.player.R
import rs.timok.player.data.db.ChannelRow
import rs.timok.player.data.db.ProgramEntity
import rs.timok.player.data.repo.VodPlayRequest
import rs.timok.player.ui.components.Clock
import rs.timok.player.ui.theme.Brand
import rs.timok.player.ui.theme.LocalAnimations
import rs.timok.player.ui.theme.scrollToItemMotion
import java.text.DateFormat
import java.util.Date

private val ChannelColumn = 230.dp
private val RowHeight = 54.dp
private val RowGap = 4.dp
private val HeaderHeight = 30.dp
private const val SLOT_MS = 30 * 60 * 1000L
/** Time span visible at once. */
private const val WINDOW_MS = 2 * 60 * 60 * 1000L
private val blockShape = RoundedCornerShape(6.dp)

/**
 * Guide grid: channels down the left, time across, programmes as blocks as wide as they last. Focus is kept by
 * hand (row + time cursor) instead of with Compose focus: blocks of different widths make the stock D-pad focus
 * jump between rows, and a single focusable root is also far cheaper on weak boxes.
 */
@Composable
fun GuideScreen(
    onWatch: (group: String, streamId: Long) -> Unit,
    onArchive: (VodPlayRequest) -> Unit,
    onProgram: (group: String, streamId: Long) -> Unit,
    onBack: () -> Unit,
    vm: GuideViewModel = hiltViewModel(),
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val programs by vm.programs.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notInArchive = stringResource(R.string.program_not_in_archive)
    val animate = LocalAnimations.current

    var focusIndex by remember { mutableIntStateOf(0) }
    var cursorMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var viewStartMs by remember { mutableLongStateOf(floorSlot(System.currentTimeMillis())) }
    var headerFocused by remember { mutableStateOf(false) }
    var startPlaced by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    BackHandler(onBack = onBack)

    val list = rows.orEmpty()
    // Start on the channel that is playing, once the rows are known; a new group starts at its top.
    LaunchedEffect(list.isNotEmpty(), group) {
        if (list.isEmpty()) return@LaunchedEffect
        focusIndex = if (!startPlaced) list.indexOfFirst { it.streamId == vm.startStreamId }.coerceAtLeast(0) else 0
        startPlaced = true
        // Each group starts on what is on now.
        cursorMs = System.currentTimeMillis()
        viewStartMs = floorSlot(cursorMs)
        listState.scrollToItem((focusIndex - 2).coerceAtLeast(0))
        vm.ensureLoaded(list, focusIndex)
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { rootFocus.requestFocus() }
    }
    // Keep the focused row on screen, with a row of context above it.
    LaunchedEffect(focusIndex, list.size) {
        if (list.isEmpty()) return@LaunchedEffect
        vm.ensureLoaded(list, focusIndex)
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return@LaunchedEffect
        val first = visible.first().index
        val last = visible.last().index
        if (focusIndex <= first || focusIndex >= last) {
            val target = if (focusIndex <= first) (focusIndex - 1).coerceAtLeast(0) else (focusIndex - (last - first) + 2).coerceAtLeast(0)
            listState.scrollToItemMotion(target, animate)
        }
    }

    val focusedRow = list.getOrNull(focusIndex)
    fun programsOf(row: ChannelRow?): List<ProgramEntity> = row?.epgChannelId?.let { programs[it] }.orEmpty()
    fun programAt(row: ChannelRow?, t: Long): ProgramEntity? = programsOf(row).firstOrNull { it.startMs <= t && it.endMs > t }
    val focusedProgram = programAt(focusedRow, cursorMs)

    fun keepCursorVisible() {
        val minView = floorSlot(vm.minMs)
        when {
            cursorMs < viewStartMs -> viewStartMs = floorSlot(cursorMs).coerceAtLeast(minView)
            cursorMs >= viewStartMs + WINDOW_MS - SLOT_MS / 2 -> viewStartMs = floorSlot(cursorMs) - SLOT_MS
        }
    }

    fun moveTime(forward: Boolean) {
        val current = programAt(focusedRow, cursorMs)
        cursorMs = if (forward) {
            (current?.endMs ?: (floorSlot(cursorMs) + SLOT_MS)).coerceAtMost(vm.maxMs)
        } else {
            val edge = current?.startMs ?: cursorMs
            val previous = programAt(focusedRow, edge - 1)
            (previous?.startMs ?: (floorSlot(edge - 1))).coerceAtLeast(vm.minMs)
        }
        keepCursorVisible()
    }

    fun open(row: ChannelRow, program: ProgramEntity?) {
        val nowMs = System.currentTimeMillis()
        val groupKey = group.key
        when {
            program == null || (program.startMs <= nowMs && program.endMs > nowMs) -> onWatch(groupKey, row.streamId)
            program.endMs <= nowMs && row.hasArchive -> scope.launch {
                val request = vm.archiveRequest(row, program)
                if (request != null) onArchive(request) else Toast.makeText(context, notInArchive, Toast.LENGTH_SHORT).show()
            }
            program.endMs <= nowMs -> onWatch(groupKey, row.streamId)
            else -> onProgram(groupKey, row.streamId)
        }
    }

    val pageSize = (listState.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)

    Column(
        Modifier
            .fillMaxSize()
            .background(Brand.Background.copy(alpha = 0.92f))
            .focusRequester(rootFocus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                if (headerFocused) {
                    val idx = groups.indexOfFirst { it.key == group }
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> groups.getOrNull(idx - 1)?.let { vm.selectGroup(it.key) }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> groups.getOrNull(idx + 1)?.let { vm.selectGroup(it.key) }
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU ->
                            headerFocused = false
                        else -> return@onKeyEvent false
                    }
                    return@onKeyEvent true
                }
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (focusIndex == 0) headerFocused = true else focusIndex--
                    KeyEvent.KEYCODE_DPAD_DOWN -> focusIndex = (focusIndex + 1).coerceAtMost((list.size - 1).coerceAtLeast(0))
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP -> focusIndex = (focusIndex - pageSize).coerceAtLeast(0)
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN ->
                        focusIndex = (focusIndex + pageSize).coerceAtMost((list.size - 1).coerceAtLeast(0))
                    KeyEvent.KEYCODE_DPAD_RIGHT -> moveTime(forward = true)
                    KeyEvent.KEYCODE_DPAD_LEFT -> moveTime(forward = false)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                        focusedRow?.let { open(it, focusedProgram) }
                    KeyEvent.KEYCODE_GUIDE -> onBack()
                    // Long groups: MENU reaches the group selector without scrolling to the top.
                    KeyEvent.KEYCODE_MENU -> headerFocused = true
                    else -> return@onKeyEvent false
                }
                true
            }
            .padding(start = 40.dp, end = 40.dp, top = 22.dp, bottom = 10.dp),
    ) {
        Details(focusedRow, focusedProgram, now)
        GroupBar(
            title = groups.firstOrNull { it.key == group }?.title.orEmpty(),
            focused = headerFocused,
            hasPrev = groups.indexOfFirst { it.key == group } > 0,
            hasNext = groups.indexOfFirst { it.key == group } < groups.lastIndex,
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val timelineWidth = maxWidth - ChannelColumn
            val dpPerMs = timelineWidth.value / WINDOW_MS
            Column {
                TimeHeader(viewStartMs, now, dpPerMs)
                when {
                    rows == null -> Hint(stringResource(R.string.loading_channels))
                    list.isEmpty() -> Hint(stringResource(R.string.no_channels_in_group))
                    else -> LazyColumn(state = listState, userScrollEnabled = false) {
                        itemsIndexed(list, key = { _, r -> r.streamId }, contentType = { _, _ -> "row" }) { i, row ->
                            GuideRow(
                                row = row,
                                programs = programsOf(row),
                                viewStartMs = viewStartMs,
                                now = now,
                                dpPerMs = dpPerMs,
                                focused = !headerFocused && i == focusIndex,
                                cursorMs = cursorMs,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Focused programme: title, time, how long it has left or until it starts, channel and description. */
@Composable
private fun Details(row: ChannelRow?, program: ProgramEntity?, now: Long) {
    val context = LocalContext.current
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Row(Modifier.fillMaxWidth().height(112.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                program?.title ?: stringResource(R.string.no_program_info),
                style = MaterialTheme.typography.headlineSmall,
                color = Brand.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (program != null) {
                val day = when {
                    DateUtils.isToday(program.startMs) -> ""
                    DateUtils.isToday(program.startMs - DateUtils.DAY_IN_MILLIS) -> stringResource(R.string.program_tomorrow) + " · "
                    DateUtils.isToday(program.startMs + DateUtils.DAY_IN_MILLIS) -> stringResource(R.string.archive_yesterday) + " · "
                    else -> DateUtils.formatDateTime(context, program.startMs, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR) + " · "
                }
                val status = when {
                    program.startMs <= now && program.endMs > now -> stringResource(R.string.info_remaining, ((program.endMs - now) / 60_000).toInt())
                    program.startMs > now -> stringResource(R.string.guide_starts_in, ((program.startMs - now) / 60_000).toInt())
                    else -> stringResource(R.string.guide_ended)
                }
                Text(
                    "$day${timeFormat.format(Date(program.startMs))}–${timeFormat.format(Date(program.endMs))}   ·   $status   ·   ${row?.name.orEmpty()}",
                    fontSize = 15.sp,
                    color = Brand.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                program.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 14.sp, color = Brand.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            } else if (row != null) {
                Text(row.name, fontSize = 15.sp, color = Brand.Accent, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Clock(color = Brand.TextSecondary, modifier = Modifier.padding(start = 24.dp))
    }
}

/** Group selector above the grid; reached with Up from the first row or MENU, Left/Right change the group. */
@Composable
private fun GroupBar(title: String, focused: Boolean, hasPrev: Boolean, hasNext: Boolean) {
    Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOfNotNull(if (hasPrev) "◀" else null, title, if (hasNext) "▶" else null).joinToString("   "),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) Color.Black else Brand.Accent,
            modifier = Modifier
                .background(if (focused) Brand.Accent else Color.Transparent, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun TimeHeader(viewStartMs: Long, now: Long, dpPerMs: Float) {
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Row(Modifier.fillMaxWidth().height(HeaderHeight)) {
        Box(Modifier.width(ChannelColumn))
        Box(
            Modifier.fillMaxHeight().weight(1f).clipToBounds().drawBehind {
                nowLine(now, viewStartMs, dpPerMs)
            },
        ) {
            var t = viewStartMs
            while (t < viewStartMs + WINDOW_MS) {
                Text(
                    timeFormat.format(Date(t)),
                    fontSize = 14.sp,
                    color = Brand.TextSecondary,
                    modifier = Modifier.offset(x = ((t - viewStartMs) * dpPerMs).dp + 4.dp, y = 4.dp),
                )
                t += SLOT_MS
            }
        }
    }
}

@Composable
private fun GuideRow(
    row: ChannelRow,
    programs: List<ProgramEntity>,
    viewStartMs: Long,
    now: Long,
    dpPerMs: Float,
    focused: Boolean,
    cursorMs: Long,
) {
    val viewEndMs = viewStartMs + WINDOW_MS
    Row(Modifier.fillMaxWidth().height(RowHeight + RowGap).padding(bottom = RowGap)) {
        ChannelCell(row, focused)
        Box(
            Modifier.fillMaxHeight().weight(1f).clipToBounds().drawBehind { nowLine(now, viewStartMs, dpPerMs) },
        ) {
            val visible = programs.filter { it.endMs > viewStartMs && it.startMs < viewEndMs }
            if (visible.isEmpty()) {
                // No guide for this time (or none at all): one block across, so the row is still clearly a row.
                Block(
                    text = stringResource(R.string.no_program_info),
                    x = 0.dp,
                    width = (WINDOW_MS * dpPerMs).dp,
                    focused = focused,
                    current = false,
                    past = false,
                )
            } else {
                for (p in visible) {
                    val start = maxOf(p.startMs, viewStartMs)
                    val end = minOf(p.endMs, viewEndMs)
                    Block(
                        text = p.title,
                        x = ((start - viewStartMs) * dpPerMs).dp,
                        width = ((end - start) * dpPerMs).dp,
                        focused = focused && p.startMs <= cursorMs && p.endMs > cursorMs,
                        current = p.startMs <= now && p.endMs > now,
                        past = p.endMs <= now,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelCell(row: ChannelRow, focused: Boolean) {
    Row(
        Modifier
            .width(ChannelColumn)
            .fillMaxHeight()
            .padding(end = 6.dp)
            .background(if (focused) Brand.SurfaceFocused else Brand.Surface.copy(alpha = 0.6f), blockShape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.number.toString(),
            fontSize = 14.sp,
            color = if (focused) Brand.Accent else Brand.TextSecondary,
            modifier = Modifier.width(40.dp),
        )
        Box(Modifier.size(width = 52.dp, height = 32.dp), contentAlignment = Alignment.Center) {
            row.logo?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Text(
            row.name,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Brand.TextPrimary,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/** One programme. Drawn, not clipped: rounded clipping of many boxes is what stutters on weak GPUs. */
@Composable
private fun Block(text: String, x: Dp, width: Dp, focused: Boolean, current: Boolean, past: Boolean) {
    val fill = when {
        focused -> Brand.Accent
        current -> Brand.Primary.copy(alpha = 0.55f)
        else -> Brand.Surface.copy(alpha = if (past) 0.35f else 0.7f)
    }
    Box(
        Modifier
            .offset(x = x)
            .width((width - 3.dp).coerceAtLeast(2.dp))
            .fillMaxHeight()
            .drawBehind {
                drawRoundRect(fill, cornerRadius = CornerRadius(6.dp.toPx()))
                if (focused) {
                    val stroke = 2.dp.toPx()
                    drawRoundRect(
                        Color.White,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                        style = Stroke(stroke),
                    )
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (width > 24.dp) {
            Text(
                text,
                fontSize = 14.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    focused -> Color.Black
                    past -> Brand.TextSecondary
                    else -> Brand.TextPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Brand.TextSecondary, modifier = Modifier.padding(top = 16.dp, start = 4.dp))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.nowLine(now: Long, viewStartMs: Long, dpPerMs: Float) {
    if (now < viewStartMs || now > viewStartMs + WINDOW_MS) return
    val x = ((now - viewStartMs) * dpPerMs).dp.toPx()
    drawLine(Brand.Accent, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
}

/** Start of the half hour [t] falls in (local time; all time zones in use have whole or half hour offsets). */
private fun floorSlot(t: Long): Long {
    val offset = java.util.TimeZone.getDefault().getOffset(t)
    return (t + offset) / SLOT_MS * SLOT_MS - offset
}
