package rs.timok.player.ui.channels

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import rs.timok.player.R
import rs.timok.player.data.db.ChannelRow
import rs.timok.player.data.repo.GroupKey
import rs.timok.player.ui.components.ChannelRowContent
import rs.timok.player.ui.components.ChannelRowModifier
import rs.timok.player.ui.components.Clock
import rs.timok.player.ui.components.NavDest
import rs.timok.player.ui.components.NavRail
import rs.timok.player.ui.components.TvClickable
import rs.timok.player.ui.theme.Brand

private data class GroupItem(val key: GroupKey, val title: String)

/** One translucent color, not a gradient: cheaper to fill on weak TV GPUs, and the panel edge fades out anyway. */
private val scrim = Brand.Background.copy(alpha = 0.88f)

/** Soft edge between the panel and the uncovered live picture. */
private val panelEdge = Brush.horizontalGradient(
    0f to Brand.Background.copy(alpha = 0.78f),
    1f to Color.Transparent,
)

/**
 * The panel stops at this width instead of always reaching the right edge, so a smaller
 * display size (Settings) leaves part of the live picture uncovered.
 */
private val PanelMaxWidth = 880.dp

/** Expanded minus collapsed width of [NavRail]. */
private val RailExpansion = 148.dp

/** Without a key press for this long, the list gives way to the playing channel in fullscreen. */
private const val IDLE_FULLSCREEN_MS = 20_000L

@Composable
fun ChannelsScreen(
    onPlay: (group: String, streamId: Long) -> Unit,
    onNavigate: (NavDest) -> Unit,
    onProgram: (group: String, streamId: Long) -> Unit,
    vm: ChannelsViewModel = hiltViewModel(),
) {
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val move by vm.move.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val playingId by vm.playingId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    BackHandler(enabled = move != null) { vm.cancelMove() }
    // Back on the top-level screen asks before leaving the app.
    var confirmExit by remember { mutableStateOf(false) }
    BackHandler(enabled = move == null) { confirmExit = true }
    if (confirmExit) {
        val activity = LocalActivity.current
        ExitDialog(onConfirm = { activity?.finish() }, onDismiss = { confirmExit = false })
    }

    // Back from fullscreen: follow the channel that was zapped to there. Runs once per entry, before the list composes.
    remember { vm.followPlayer() }
    // Restore focus to the last channel only once per screen entry, not on every group switch.
    var restoreFocus by remember { mutableStateOf(true) }
    val groupFocus = remember { FocusRequester() }

    // Left alone, the list goes fullscreen on the channel playing behind it, like an overlay that times out.
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lastInput, playingId, move, confirmExit) {
        if (playingId == null || move != null || confirmExit) return@LaunchedEffect
        delay(IDLE_FULLSCREEN_MS)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        vm.fullscreenTarget()?.let { (groupKey, streamId) -> onPlay(groupKey, streamId) }
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val favoritesTitle = stringResource(R.string.group_favorites)
    val allTitle = stringResource(R.string.group_all)
    val recentTitle = stringResource(R.string.group_recent)
    val groupItems = remember(categories, favoritesTitle, allTitle, recentTitle) {
        buildList {
            add(GroupItem(GroupKey.Favorites, favoritesTitle))
            add(GroupItem(GroupKey.Recent, recentTitle))
            add(GroupItem(GroupKey.All, allTitle))
            categories.forEach { add(GroupItem(GroupKey.Category(it.categoryId), it.name)) }
        }
    }
    val groupTitle = groupItems.firstOrNull { it.key == group }?.title.orEmpty()
    val list = move?.items ?: rows

    // Nothing to restore in an empty group: start on the group column instead.
    LaunchedEffect(list?.isEmpty()) {
        if (restoreFocus && list != null && list.isEmpty()) {
            restoreFocus = false
            withFrameNanos { }
            runCatching { groupFocus.requestFocus() }
        }
    }

    // The rail widens when focused; grow the panel with it instead of squeezing the channel list.
    var railFocused by remember { mutableStateOf(false) }
    val panelWidth by animateDpAsState(PanelMaxWidth + if (railFocused) RailExpansion else 0.dp, tween(150), label = "panel")
    Row(
        Modifier.fillMaxSize().onPreviewKeyEvent {
            lastInput = System.currentTimeMillis()
            false
        },
    ) {
        Row(Modifier.widthIn(max = panelWidth).fillMaxHeight().background(scrim).padding(vertical = 24.dp)) {
            Box(Modifier.onFocusChanged { railFocused = it.hasFocus }) {
                NavRail(selected = NavDest.TV, onNavigate = onNavigate, enabled = move == null)
            }

            // Groups column
            Column(Modifier.width(270.dp).fillMaxHeight().padding(start = 12.dp)) {
                Text(
                    stringResource(R.string.brand_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = Brand.Accent,
                    modifier = Modifier.padding(start = 14.dp, bottom = 16.dp),
                )
                GroupList(
                    items = groupItems,
                    selected = group,
                    selectedRequester = groupFocus,
                    focusEnabled = move == null,
                    onFocusGroup = vm::selectGroup,
                )
            }
            Spacer(Modifier.width(16.dp))

            // Channels column
            Column(Modifier.weight(1f).fillMaxHeight().padding(end = 32.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (move != null) stringResource(R.string.move_mode_hint) else groupTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = if (move != null) Brand.Accent else Brand.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    when {
                        refreshing -> Text(stringResource(R.string.refreshing), color = Brand.TextSecondary)
                        rows != null && move == null -> Text(rows!!.size.toString(), color = Brand.TextSecondary)
                    }
                    Clock(color = Brand.TextSecondary, modifier = Modifier.padding(start = 20.dp))
                }
                when {
                    list == null -> Text(
                        stringResource(R.string.loading_channels),
                        color = Brand.TextSecondary,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                    playlist == null -> Unit
                    list.isEmpty() -> Text(
                        stringResource(
                            when (group) {
                                GroupKey.Favorites -> R.string.favorites_empty
                                GroupKey.Recent -> R.string.recent_empty
                                else -> R.string.no_channels_in_group
                            }
                        ),
                        color = Brand.TextSecondary,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                    // Each group keeps its own scroll position (restored when coming back from the player).
                    else -> key(group) {
                        ChannelList(
                            rows = list,
                            now = now,
                            playingId = playingId,
                            movingId = move?.movingId,
                            focusTargetId = vm.focusTargetId,
                            groupFocus = groupFocus,
                            restoreFocus = restoreFocus,
                            onFocusRestored = { found ->
                                restoreFocus = false
                                if (!found) runCatching { groupFocus.requestFocus() }
                            },
                            onFocused = { vm.focusTargetId = it },
                            onProgram = { row -> onProgram(playGroup(group), row.streamId) },
                            onClick = { row ->
                                scope.launch {
                                    if (vm.onChannelClick(row)) onPlay(playGroup(group), row.streamId)
                                }
                            },
                            onLongClick = vm::onLongPress,
                            onMove = vm::moveBy,
                            onCommitMove = vm::commitMove,
                        )
                    }
                }
            }
        }
        Box(Modifier.width(140.dp).fillMaxHeight().background(panelEdge))
    }

    message?.let { Toast(it) }
}

@Composable
private fun GroupList(
    items: List<GroupItem>,
    selected: GroupKey?,
    selectedRequester: FocusRequester,
    focusEnabled: Boolean,
    onFocusGroup: (GroupKey) -> Unit,
) {
    val listState = rememberLazyListState()
    // The selected group must be composed (on screen) so Left from the channel list can focus it.
    LaunchedEffect(selected, items.size) {
        val idx = items.indexOfFirst { it.key == selected }
        if (idx >= 0 && idx !in listState.layoutInfo.visibleItemsInfo.map { it.index }) {
            listState.scrollToItem((idx - 3).coerceAtLeast(0))
        }
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
    ) {
        items(items, key = { it.key.key }) { item ->
            val isSelected = item.key == selected
            var mod = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused && focusEnabled) onFocusGroup(item.key) }
            if (isSelected) mod = mod.focusRequester(selectedRequester)
            TvClickable(onClick = { onFocusGroup(item.key) }, modifier = mod) {
                Text(
                    item.title,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize = 16.sp,
                    color = if (isSelected) Brand.Accent else Brand.TextPrimary,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ChannelList(
    rows: List<ChannelRow>,
    now: Long,
    playingId: Long?,
    movingId: Long?,
    focusTargetId: Long?,
    groupFocus: FocusRequester,
    restoreFocus: Boolean,
    onFocusRestored: (found: Boolean) -> Unit,
    onFocused: (Long) -> Unit,
    onProgram: (ChannelRow) -> Unit,
    onClick: (ChannelRow) -> Unit,
    onLongClick: (ChannelRow) -> Unit,
    onMove: (Int) -> Unit,
    onCommitMove: () -> Unit,
) {
    val listState = rememberLazyListState()
    val targetRequester = remember { FocusRequester() }
    // Captured when a restore or move starts: focus events meanwhile must not retarget it.
    val targetId = remember(movingId, restoreFocus) { movingId ?: focusTargetId }
    val windowInfo = LocalWindowInfo.current

    // Restore focus on the last played/focused channel when the screen is entered or a move starts.
    LaunchedEffect(movingId, restoreFocus) {
        if (movingId == null && !restoreFocus) return@LaunchedEffect
        val idx = rows.indexOfFirst { it.streamId == targetId }
        if (idx < 0) {
            onFocusRestored(false)
            return@LaunchedEffect
        }
        if (idx !in listState.layoutInfo.visibleItemsInfo.map { it.index }) listState.scrollToItem((idx - 2).coerceAtLeast(0))
        withFrameNanos { }
        // On a cold start the window gains focus later and would reset it to the first item; wait for it.
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        runCatching { targetRequester.requestFocus() }
        onFocusRestored(true)
    }
    // Keep the moving row visible while it travels.
    LaunchedEffect(movingId, rows) {
        if (movingId == null) return@LaunchedEffect
        val idx = rows.indexOfFirst { it.streamId == movingId }
        val visible = listState.layoutInfo.visibleItemsInfo
        if (idx >= 0 && visible.isNotEmpty() && (idx <= visible.first().index || idx >= visible.last().index)) {
            listState.animateScrollToItem((idx - 2).coerceAtLeast(0))
        }
        runCatching { targetRequester.requestFocus() }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp),
    ) {
        itemsIndexed(rows, key = { _, r -> r.streamId }, contentType = { _, _ -> "channel" }) { _, row ->
            val isMoving = row.streamId == movingId
            var mod = ChannelRowModifier
                .onFocusChanged { if (it.isFocused) onFocused(row.streamId) }
                // Left always returns to the selected group, not whichever group sits at the same height.
                .focusProperties { left = groupFocus }
            if (row.streamId == targetId) mod = mod.focusRequester(targetRequester)
            if (movingId == null) {
                // Right opens the channel's full guide (the list is the last column, nothing else lies there).
                mod = mod.onPreviewKeyEvent { e ->
                    if (e.key != Key.DirectionRight) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown) onProgram(row)
                    true
                }
            }
            if (isMoving) {
                mod = mod.onPreviewKeyEvent { e ->
                    when (e.key) {
                        Key.DirectionUp -> { if (e.type == KeyEventType.KeyDown) onMove(-1); true }
                        Key.DirectionDown -> { if (e.type == KeyEventType.KeyDown) onMove(1); true }
                        Key.DirectionLeft, Key.DirectionRight -> true
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            // The long-press that started the move ends with a key-up and repeats; ignore those.
                            if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) onCommitMove()
                            true
                        }
                        else -> false
                    }
                }
            }
            TvClickable(
                onClick = { if (movingId == null) onClick(row) },
                onLongClick = { if (movingId == null) onLongClick(row) },
                highlighted = isMoving,
                modifier = mod,
            ) {
                ChannelRowContent(row, now, playing = row.streamId == playingId)
            }
        }
    }
}

/**
 * Group the player zaps in. "Recently watched" reorders itself with every channel opened, so up/down there would
 * jump around; such channels play in "All channels" instead.
 */
private fun playGroup(group: GroupKey?): String = when (group) {
    null, GroupKey.Recent -> GroupKey.All.key
    else -> group.key
}

@Composable
private fun Toast(text: String) {
    Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            text,
            color = Brand.TextPrimary,
            modifier = Modifier
                .background(Brand.SurfaceFocused, RoundedCornerShape(24.dp))
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}
