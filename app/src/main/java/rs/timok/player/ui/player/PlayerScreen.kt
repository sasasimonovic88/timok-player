package rs.timok.player.ui.player

import rs.timok.player.player.ExternalPlayer
import androidx.compose.ui.platform.LocalContext
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import rs.timok.player.R
import rs.timok.player.data.db.ChannelRow
import rs.timok.player.data.db.ProgramEntity
import rs.timok.player.player.PlaybackStatus
import rs.timok.player.player.LivePlayer
import rs.timok.player.ui.components.ChannelLogo
import rs.timok.player.ui.components.ChannelRowContent
import rs.timok.player.ui.components.ChannelRowModifier
import rs.timok.player.ui.components.Clock
import rs.timok.player.ui.components.ProgramProgress
import rs.timok.player.ui.components.TvClickable
import rs.timok.player.ui.components.rememberTimeFormat
import rs.timok.player.ui.theme.Brand
import java.util.Date

private const val INFO_TIMEOUT_MS = 5_000L
private const val OVERLAY_TIMEOUT_MS = 8_000L
private const val OPTIONS_TIMEOUT_MS = 15_000L

private fun Int.isOkKey() =
    this == KeyEvent.KEYCODE_DPAD_CENTER || this == KeyEvent.KEYCODE_ENTER || this == KeyEvent.KEYCODE_NUMPAD_ENTER

@Composable
fun PlayerScreen(
    onExit: () -> Unit,
    onArchive: (streamId: Long, name: String, logo: String?) -> Unit,
    onProgram: (group: String, streamId: Long) -> Unit,
    vm: PlayerViewModel = hiltViewModel(),
) {
    val appContext = LocalContext.current
    val externalAvailable = remember { ExternalPlayer.available(appContext) }
    val channels by vm.channels.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val nowNext by vm.nowNext.collectAsStateWithLifecycle()
    val streamInfo by vm.streamInfo.collectAsStateWithLifecycle()
    val slowDecoding by vm.slowDecoding.collectAsStateWithLifecycle()
    rs.timok.player.ui.components.SlowDecodingNotice(slowDecoding)
    val status by vm.status.collectAsStateWithLifecycle()
    val numberInput by vm.numberInput.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val zapCounter by vm.zapCounter.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val aspect by vm.aspect.collectAsStateWithLifecycle()
    val sleepMinutes by vm.sleepMinutes.collectAsStateWithLifecycle()
    val sleepRemainingMs by vm.sleepRemainingMs.collectAsStateWithLifecycle()

    var overlayVisible by remember { mutableStateOf(false) }
    var infoVisible by remember { mutableStateOf(true) }
    var infoToken by remember { mutableIntStateOf(0) }
    var lastOverlayInput by remember { mutableLongStateOf(0L) }
    var optionsVisible by remember { mutableStateOf(false) }
    // Long-press OK opens options; its repeats and key-up must not reach the panel that just took focus.
    var okLongPressed by remember { mutableStateOf(false) }
    var swallowOk by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }

    LaunchedEffect(zapCounter) { infoVisible = true; infoToken++ }
    LaunchedEffect(infoToken, infoVisible) {
        if (infoVisible) {
            delay(INFO_TIMEOUT_MS)
            infoVisible = false
        }
    }
    LaunchedEffect(overlayVisible, lastOverlayInput) {
        if (overlayVisible) {
            delay(OVERLAY_TIMEOUT_MS)
            overlayVisible = false
        }
    }
    LaunchedEffect(optionsVisible, lastOverlayInput) {
        if (optionsVisible) {
            delay(OPTIONS_TIMEOUT_MS)
            optionsVisible = false
        }
    }
    LaunchedEffect(overlayVisible, optionsVisible) {
        if (!overlayVisible && !optionsVisible) {
            withFrameNanos { }
            runCatching { rootFocus.requestFocus() }
        }
    }

    // Coming back from catch-up: VOD took the connection, reopen the live channel.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.resumeLive() }

    BackHandler {
        when {
            optionsVisible -> optionsVisible = false
            overlayVisible -> overlayVisible = false
            infoVisible -> infoVisible = false
            else -> onExit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (overlayVisible || optionsVisible) lastOverlayInput = System.currentTimeMillis()
                val code = e.nativeKeyEvent.keyCode
                when {
                    swallowOk && code.isOkKey() -> {
                        if (e.type == KeyEventType.KeyUp) swallowOk = false
                        true
                    }
                    optionsVisible && code == KeyEvent.KEYCODE_MENU -> {
                        if (e.type == KeyEventType.KeyDown) optionsVisible = false
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable()
            .onKeyEvent { e ->
                if (overlayVisible || optionsVisible) return@onKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                fun openOptions() {
                    optionsVisible = true
                    infoVisible = false
                }
                // OK acts on key-up: acting on key-down moves focus into the list, and the
                // following key-up would then "click" the focused row and close the list again.
                if (code.isOkKey()) {
                    val repeat = e.nativeKeyEvent.repeatCount
                    when {
                        e.type == KeyEventType.KeyDown && repeat == 0 -> okLongPressed = false
                        e.type == KeyEventType.KeyDown && !okLongPressed -> {
                            okLongPressed = true
                            swallowOk = true
                            openOptions()
                        }
                        e.type == KeyEventType.KeyUp && !okLongPressed && !vm.commitNumber() -> {
                            overlayVisible = true
                            infoVisible = false
                        }
                    }
                    return@onKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (code) {
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { vm.onDigit(code - KeyEvent.KEYCODE_0, e.nativeKeyEvent.eventTime); true }
                    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { vm.onDigit(code - KeyEvent.KEYCODE_NUMPAD_0, e.nativeKeyEvent.eventTime); true }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { vm.zap(-1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { vm.zap(1); true }
                    KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROG_GREEN -> {
                        current?.let { onProgram(vm.groupKey, it.streamId) }
                        true
                    }
                    KeyEvent.KEYCODE_LAST_CHANNEL -> { vm.previousChannel(); true }
                    // Color keys: red favorite, green guide (above), yellow subtitles, blue picture format.
                    KeyEvent.KEYCODE_PROG_RED -> { vm.toggleFavorite(); true }
                    KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_CAPTIONS -> { vm.toggleSubtitles(); true }
                    KeyEvent.KEYCODE_PROG_BLUE -> { vm.cycleAspectWithMessage(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (!vm.commitNumber()) {
                            overlayVisible = true
                            infoVisible = false
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> { infoVisible = true; infoToken++; true }
                    KeyEvent.KEYCODE_MENU -> { openOptions(); true }
                    else -> false
                }
            },
    ) {
        StatusIndicator(status)

        if (numberInput.isNotEmpty()) {
            Text(
                numberInput,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = infoVisible && !overlayVisible && !optionsVisible && current != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            current?.let { InfoBar(it, nowNext, streamInfo) }
        }

        // Kept composed while hidden so opening it is instant on slow boxes.
        // While hidden it cannot get focus: the root consumes every D-pad key.
        ChannelOverlay(
            visible = overlayVisible,
            channels = channels,
            currentId = current?.streamId,
            onSelect = {
                vm.selectChannel(it.streamId)
                overlayVisible = false
            },
            modifier = Modifier.align(Alignment.CenterStart),
        )

        AnimatedVisibility(
            visible = optionsVisible && current != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            current?.let { channel ->
                OptionsPanel(
                    title = channel.name,
                    isFavorite = channel.isFavorite,
                    visible = optionsVisible,
                    tracks = tracks,
                    aspect = aspect,
                    sleepMinutes = sleepMinutes,
                    sleepRemainingMs = sleepRemainingMs,
                    onToggleFavorite = {
                        vm.toggleFavorite()
                        optionsVisible = false
                    },
                    onSelectTrack = vm::selectTrack,
                    onSubtitlesOff = vm::disableSubtitles,
                    onCycleAspect = vm::cycleAspect,
                    onCycleSleep = vm::cycleSleepTimer,
                    onProgram = {
                        optionsVisible = false
                        onProgram(vm.groupKey, channel.streamId)
                    },
                    onArchive = if (channel.hasArchive) {
                        {
                            optionsVisible = false
                            onArchive(channel.streamId, channel.name, channel.logo)
                        }
                    } else null,
                    onReload = {
                        vm.reload()
                        optionsVisible = false
                    },
                    onExternal = if (externalAvailable) {
                        {
                            optionsVisible = false
                            vm.openExternal()
                        }
                    } else null,
                )
            }
        }

        message?.let {
            Text(
                it,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp)
                    .background(Brand.SurfaceFocused, RoundedCornerShape(24.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun StatusIndicator(status: PlaybackStatus) {
    val text = when (status) {
        PlaybackStatus.Buffering -> stringResource(R.string.player_buffering)
        is PlaybackStatus.Reconnecting -> stringResource(R.string.player_reconnecting, status.attempt)
        is PlaybackStatus.Failed -> when (status.reason) {
            LivePlayer.REASON_RESOLUTION -> stringResource(R.string.player_resolution_limited)
            LivePlayer.REASON_VIDEO_FORMAT -> stringResource(R.string.player_video_format_unsupported)
            LivePlayer.REASON_SOURCE -> stringResource(R.string.player_source_error)
            else -> stringResource(R.string.player_error, status.reason)
        }
        else -> return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = if (status is PlaybackStatus.Failed) Brand.Error else Color.White,
            fontSize = 18.sp,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun ChannelOverlay(
    visible: Boolean,
    channels: List<ChannelRow>,
    currentId: Long?,
    onSelect: (ChannelRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    val now = remember(visible) { System.currentTimeMillis() }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(120), label = "overlay")

    // Keep the current channel near the top even while hidden, so opening needs no scroll.
    LaunchedEffect(currentId, channels.size) {
        val idx = channels.indexOfFirst { it.streamId == currentId }
        if (idx >= 0 && !visible) listState.scrollToItem((idx - 3).coerceAtLeast(0))
    }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        val idx = channels.indexOfFirst { it.streamId == currentId }
        if (idx >= 0 && idx !in listState.layoutInfo.visibleItemsInfo.map { it.index }) {
            listState.scrollToItem((idx - 3).coerceAtLeast(0))
            withFrameNanos { }
        }
        runCatching { currentFocus.requestFocus() }
    }

    Box(
        modifier
            .focusGroup()
            .focusProperties { onExit = { if (visible) cancelFocusChange() } }
            .graphicsLayer { this.alpha = alpha }
            .fillMaxHeight()
            .width(580.dp)
            // Same glass panel as the info bar and options.
            .padding(start = 28.dp, top = 28.dp, bottom = 28.dp)
            .clip(panelShape)
            .background(glassTint)
            .background(glassHighlight)
            .border(1.dp, Color.White.copy(alpha = 0.14f), panelShape),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(channels, key = { it.streamId }, contentType = { "channel" }) { row ->
                val mod = if (row.streamId == currentId) ChannelRowModifier.focusRequester(currentFocus) else ChannelRowModifier
                TvClickable(onClick = { onSelect(row) }, selected = row.streamId == currentId, modifier = mod) {
                    ChannelRowContent(row, now, compact = true)
                }
            }
        }
    }
}
