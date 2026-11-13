package rs.timok.player.ui.vod

import rs.timok.player.ui.player.StreamBadges
import rs.timok.player.player.StreamInfo
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import rs.timok.player.R
import rs.timok.player.player.PlaybackStatus
import rs.timok.player.player.LivePlayer
import rs.timok.player.ui.player.OptionsPanel
import rs.timok.player.ui.player.glassHighlight
import rs.timok.player.ui.player.glassTint
import rs.timok.player.ui.player.panelShape
import rs.timok.player.ui.theme.Brand

private const val CONTROLS_TIMEOUT_MS = 4_000L
private const val OPTIONS_TIMEOUT_MS = 15_000L

private fun Int.isOkKey() =
    this == KeyEvent.KEYCODE_DPAD_CENTER || this == KeyEvent.KEYCODE_ENTER || this == KeyEvent.KEYCODE_NUMPAD_ENTER

/** Seek step grows while the key is held: 10 s, then 30 s, then 1 min. */
private fun seekStep(repeatCount: Int) = when {
    repeatCount > 15 -> 60_000L
    repeatCount > 4 -> 30_000L
    else -> 10_000L
}

@Composable
fun VodPlayerScreen(onExit: () -> Unit, vm: VodPlayerViewModel = hiltViewModel()) {
    val request by vm.request.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val position by vm.positionMs.collectAsStateWithLifecycle()
    val duration by vm.durationMs.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val finished by vm.finished.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val aspect by vm.aspect.collectAsStateWithLifecycle()
    val sleepMinutes by vm.sleepMinutes.collectAsStateWithLifecycle()
    val sleepRemainingMs by vm.sleepRemainingMs.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val streamInfo by vm.streamInfo.collectAsStateWithLifecycle()
    val slowDecoding by vm.slowDecoding.collectAsStateWithLifecycle()
    rs.timok.player.ui.components.SlowDecodingNotice(slowDecoding)

    var controlsVisible by remember { mutableStateOf(true) }
    var optionsVisible by remember { mutableStateOf(false) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var okLongPressed by remember { mutableStateOf(false) }
    var swallowOk by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }

    LaunchedEffect(finished) { if (finished) onExit() }
    // Controls hide while playing; they stay up when paused.
    LaunchedEffect(controlsVisible, lastInput, playing) {
        if (controlsVisible && playing) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(optionsVisible, lastInput) {
        if (optionsVisible) {
            delay(OPTIONS_TIMEOUT_MS)
            optionsVisible = false
        }
    }
    LaunchedEffect(optionsVisible) {
        if (!optionsVisible) {
            withFrameNanos { }
            runCatching { rootFocus.requestFocus() }
        }
    }

    BackHandler {
        if (optionsVisible) {
            optionsVisible = false
        } else {
            vm.exit()
            onExit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                lastInput = System.currentTimeMillis()
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
                if (optionsVisible) return@onKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                val repeat = e.nativeKeyEvent.repeatCount
                // OK on key-up so a long press can open options instead (same as live TV).
                if (code.isOkKey()) {
                    when {
                        e.type == KeyEventType.KeyDown && repeat == 0 -> okLongPressed = false
                        e.type == KeyEventType.KeyDown && !okLongPressed -> {
                            okLongPressed = true
                            swallowOk = true
                            optionsVisible = true
                        }
                        e.type == KeyEventType.KeyUp && !okLongPressed -> {
                            vm.togglePause()
                            controlsVisible = true
                        }
                    }
                    return@onKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { vm.seekBy(-seekStep(repeat)); controlsVisible = true; true }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { vm.seekBy(seekStep(repeat)); controlsVisible = true; true }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { vm.togglePause(); controlsVisible = true; true }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { vm.setPlaying(true); controlsVisible = true; true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { vm.setPlaying(false); controlsVisible = true; true }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> { controlsVisible = true; true }
                    KeyEvent.KEYCODE_MENU -> { optionsVisible = true; true }
                    else -> false
                }
            },
    ) {
        val statusText = when (val s = status) {
            PlaybackStatus.Buffering -> stringResource(R.string.player_buffering)
            is PlaybackStatus.Reconnecting -> stringResource(R.string.player_reconnecting, s.attempt)
            // The stall watchdog gives up on VOD that never started: usually the box cannot decode it (e.g. 4K HEVC).
            is PlaybackStatus.Failed -> when (s.reason) {
                "timeout" -> stringResource(R.string.player_unsupported)
                LivePlayer.REASON_RESOLUTION -> stringResource(R.string.player_resolution_limited)
                LivePlayer.REASON_VIDEO_FORMAT -> stringResource(R.string.player_video_format_unsupported)
                // Catch-up: the server lists the programme but has no recording for it.
                LivePlayer.REASON_SOURCE -> stringResource(if (request.url != null) R.string.archive_unavailable else R.string.player_source_error)
                else -> stringResource(R.string.player_error, s.reason)
            }
            else -> null
        }
        statusText?.let {
            Text(
                it,
                color = if (status is PlaybackStatus.Failed) Brand.Error else Color.White,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }

        AnimatedVisibility(
            visible = (controlsVisible || !playing) && !optionsVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Controls(
                title = request.title,
                subtitle = request.season?.let { s ->
                    listOfNotNull(stringResource(R.string.vod_episode_label, s, request.episode ?: 0), request.episodeTitle).joinToString("  ·  ")
                },
                info = info,
                streamInfo = streamInfo,
                positionMs = position,
                durationMs = duration,
                playing = playing,
            )
        }

        AnimatedVisibility(
            visible = optionsVisible,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            OptionsPanel(
                title = request.title,
                visible = optionsVisible,
                isFavorite = null,
                tracks = tracks,
                aspect = aspect,
                sleepMinutes = sleepMinutes,
                sleepRemainingMs = sleepRemainingMs,
                onToggleFavorite = {},
                onSelectTrack = vm::selectTrack,
                onSubtitlesOff = vm::disableSubtitles,
                onCycleAspect = vm::cycleAspect,
                onCycleSleep = vm::cycleSleepTimer,
                onReload = {
                    vm.reload()
                    optionsVisible = false
                },
            )
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
private fun Controls(
    title: String,
    subtitle: String?,
    info: VodInfo?,
    streamInfo: StreamInfo,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 28.dp)
            .clip(panelShape)
            .background(glassTint)
            .background(glassHighlight)
            .border(1.dp, Color.White.copy(alpha = 0.14f), panelShape)
            .padding(horizontal = 28.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            info?.poster?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(end = 20.dp)
                        .size(width = 84.dp, height = 126.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brand.Surface),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, fontSize = 16.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                info?.meta?.let {
                    Text(it, fontSize = 14.sp, color = Brand.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                info?.plot?.let {
                    Text(it, fontSize = 14.sp, color = Brand.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                StreamBadges(streamInfo, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (playing) "❚❚" else "▶", fontSize = 20.sp, color = Brand.Accent, modifier = Modifier.width(36.dp))
            Text(formatDuration(positionMs), fontSize = 16.sp, color = Color.White)
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.25f)),
            ) {
                val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Brand.Accent))
            }
            Text(if (durationMs > 0) formatDuration(durationMs) else "--:--", fontSize = 16.sp, color = Brand.TextSecondary)
            Spacer(Modifier.width(4.dp))
        }
    }
}
