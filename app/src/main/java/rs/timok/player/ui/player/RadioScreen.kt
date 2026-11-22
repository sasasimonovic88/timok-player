package rs.timok.player.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import rs.timok.player.R
import rs.timok.player.data.db.ChannelRow
import rs.timok.player.data.db.ProgramEntity
import rs.timok.player.data.repo.ChannelRepository
import rs.timok.player.data.repo.GroupKey
import rs.timok.player.data.repo.PlaylistRepository
import rs.timok.player.data.settings.AppPrefs
import rs.timok.player.player.PlaybackController
import rs.timok.player.ui.components.Clock
import rs.timok.player.ui.components.rememberTimeFormat
import rs.timok.player.ui.theme.Brand
import rs.timok.player.ui.theme.LocalAnimations
import java.util.Date
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RadioViewModel @Inject constructor(
    playlists: PlaylistRepository,
    channelRepo: ChannelRepository,
    playback: PlaybackController,
) : ViewModel() {

    val audioOnly: StateFlow<Boolean> = playback.livePlayer.audioOnly

    private val playlistId = playlists.activePlaylist.mapLatest { it?.id }.distinctUntilChanged()

    val channel: StateFlow<ChannelRow?> = combine(playlistId, playback.currentStreamId) { p, id -> p to id }
        .distinctUntilChanged()
        .mapLatest { (p, id) -> if (p == null || id == null) null else channelRepo.channelRow(p, id, GroupKey.All) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val nowNext: StateFlow<List<ProgramEntity>> = combine(playlistId, channel) { p, c -> p to c?.epgChannelId }
        .distinctUntilChanged()
        .flatMapLatest { (p, key) -> if (p == null || key == null) flowOf(emptyList()) else channelRepo.nowNext(p, key) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * Shown in place of the (black) picture while a live channel has sound but no video: logo, name, a small
 * equalizer and the current programme. The whole block drifts slowly so a radio left on for hours does not
 * mark the TV. Drawn above the video view, below every screen.
 */
@Composable
fun RadioScreen(vm: RadioViewModel = hiltViewModel()) {
    val audioOnly by vm.audioOnly.collectAsStateWithLifecycle()
    if (!audioOnly) return
    val channel by vm.channel.collectAsStateWithLifecycle()
    val nowNext by vm.nowNext.collectAsStateWithLifecycle()
    val clockOverlay by AppPrefs.clockOverlay.collectAsState()
    val animate = LocalAnimations.current
    val accent = Brand.Accent

    val transition = rememberInfiniteTransition(label = "radio")
    val driftX by transition.animateFloat(-1f, 1f, infiniteRepeatable(tween(97_000, easing = LinearEasing), RepeatMode.Reverse), label = "dx")
    val driftY by transition.animateFloat(-1f, 1f, infiniteRepeatable(tween(61_000, easing = LinearEasing), RepeatMode.Reverse), label = "dy")

    Box(Modifier.fillMaxSize().background(Brand.Background)) {
        // The player's own clock (Settings → Appearance) already sits in this corner when it is on.
        if (!clockOverlay) {
            Clock(color = Brand.TextSecondary, modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 32.dp))
        }
        Column(
            Modifier
                .align(Alignment.Center)
                .offset(x = if (animate) (driftX * 48).dp else 0.dp, y = if (animate) (driftY * 28).dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val ringColor = accent
            Box(
                Modifier
                    .size(LOGO_SIZE)
                    .drawBehind {
                        // Radio waves around the logo; drawn outside the box on purpose.
                        val center = Offset(size.width / 2, size.height / 2)
                        listOf(113.dp to 0.10f, 176.dp to 0.07f, 247.dp to 0.04f).forEach { (r, a) ->
                            drawCircle(ringColor.copy(alpha = a), radius = r.toPx(), center = center, style = Stroke(1.5.dp.toPx()))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Logo(channel?.logo)
            }
            Text(
                channel?.name.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                color = Brand.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 40.dp).width(640.dp),
            )
            Row(
                Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Equalizer(animate)
                Text(stringResource(R.string.radio_label), style = MaterialTheme.typography.titleMedium, color = accent)
            }
            val now = nowNext.firstOrNull()?.takeIf { it.startMs <= System.currentTimeMillis() }
            if (now != null) {
                val timeFormat = rememberTimeFormat()
                Text(
                    stringResource(R.string.radio_now, now.title, timeFormat.format(Date(now.endMs))),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Brand.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp).width(640.dp),
                )
                val span = (now.endMs - now.startMs).coerceAtLeast(1)
                val progress = ((System.currentTimeMillis() - now.startMs).toFloat() / span).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 10.dp).width(260.dp).height(4.dp).background(Brand.Surface, RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(accent, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

/** Channel logo on a light tile once it has loaded; a radio icon until then, and when there is none. */
@Composable
private fun Logo(logo: String?) {
    var loaded by remember(logo) { mutableStateOf(false) }
    if (!loaded) {
        Box(Modifier.fillMaxSize().background(Brand.Surface, CircleShape), contentAlignment = Alignment.Center) {
            Image(
                painterResource(R.drawable.ic_radio),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Brand.Accent),
                modifier = Modifier.size(64.dp),
            )
        }
    }
    if (!logo.isNullOrBlank()) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (loaded) Modifier.background(Brand.TextPrimary, RoundedCornerShape(24.dp)) else Modifier)
                .padding(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Five bars bouncing out of step; still when animations are off. */
@Composable
private fun Equalizer(animate: Boolean) {
    val transition = rememberInfiniteTransition(label = "eq")
    Row(Modifier.height(BAR_MAX), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
        listOf(0, 300, 600, 150, 450).forEachIndexed { i, delay ->
            val level by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse, StartOffset(delay)),
                label = "bar$i",
            )
            val still = listOf(0.5f, 0.9f, 0.6f, 1f, 0.4f)[i]
            Box(
                Modifier
                    .width(6.dp)
                    .height(BAR_MAX * (if (animate) level else still))
                    .background(Brand.Accent, RoundedCornerShape(2.dp)),
            )
        }
    }
}

private val LOGO_SIZE: Dp = 150.dp
private val BAR_MAX: Dp = 26.dp
