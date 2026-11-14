package rs.timok.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import rs.timok.player.data.settings.AppPrefs
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import rs.timok.player.R
import rs.timok.player.data.db.ChannelRow
import rs.timok.player.data.db.ProgramEntity
import rs.timok.player.player.StreamInfo
import rs.timok.player.ui.components.ChannelLogo
import rs.timok.player.ui.components.Clock
import rs.timok.player.ui.components.ProgramProgress
import rs.timok.player.ui.components.rememberTimeFormat
import rs.timok.player.ui.theme.Brand
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal val panelShape = RoundedCornerShape(20.dp)

// Real backdrop blur is impossible over a SurfaceView (and needs API 31), so the "glass" is a
// tinted translucent panel with a soft top highlight and a thin light rim.
internal val glassTint = Brush.verticalGradient(
    listOf(Color(0xFF1B2744).copy(alpha = 0.80f), Brand.Background.copy(alpha = 0.88f)),
)
internal val glassHighlight = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.07f),
    0.35f to Color.Transparent,
)

/** Bottom info panel: channel, now/next with progress, and technical badges for the stream. */
@Composable
internal fun InfoBar(channel: ChannelRow, nowNext: List<ProgramEntity>, info: StreamInfo) {
    val timeFormat = rememberTimeFormat()
    val dateFormat = remember {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM")
        SimpleDateFormat(pattern, Locale.getDefault())
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val current = nowNext.firstOrNull { it.startMs <= now && it.endMs > now }
    val next = nowNext.firstOrNull { it.startMs >= (current?.endMs ?: now) && it !== current }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 28.dp)
            .clip(panelShape)
            .background(glassTint)
            .background(glassHighlight)
            .border(1.dp, Color.White.copy(alpha = 0.14f), panelShape)
            .padding(horizontal = 28.dp, vertical = 20.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ChannelLogo(channel.logo, Modifier.size(width = 150.dp, height = 100.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.number.toString(), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Brand.Accent)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        channel.name,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (channel.isFavorite) Text("  ★", color = Brand.Accent, fontSize = 20.sp)
                    Spacer(Modifier.weight(1f))
                    Text(dateFormat.format(Date(now)), color = Brand.TextSecondary, fontSize = 16.sp)
                    Spacer(Modifier.width(12.dp))
                    Clock(color = Color.White)
                }

                StreamBadges(info, channel.hasArchive, Modifier.padding(top = 8.dp))

                if (current != null) {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${timeFormat.format(Date(current.startMs))} – ${timeFormat.format(Date(current.endMs))}",
                            fontSize = 17.sp,
                            color = Brand.TextSecondary,
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            current.title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        val minutesLeft = ((current.endMs - now + 59_999) / 60_000).toInt()
                        Text(stringResource(R.string.info_remaining, minutesLeft), fontSize = 16.sp, color = Brand.Accent)
                    }
                    ProgramProgress(
                        (now - current.startMs).toFloat() / (current.endMs - current.startMs).coerceAtLeast(1),
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    current.description?.let {
                        Text(it, fontSize = 15.sp, color = Brand.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text(
                        stringResource(R.string.no_program_info),
                        fontSize = 17.sp,
                        color = Brand.TextSecondary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (next != null) {
                    Text(
                        stringResource(R.string.next_program, "${timeFormat.format(Date(next.startMs))}  ${next.title}"),
                        fontSize = 16.sp,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** Technical facts of the running stream (resolution, codecs, bitrate...); shared by live TV and VOD. */
@Composable
internal fun StreamBadges(info: StreamInfo, hasArchive: Boolean = false, modifier: Modifier = Modifier) {
    val audioTracks = stringResource(R.string.badge_audio_tracks, info.audioTrackCount)
    val subtitles = stringResource(R.string.badge_subtitles)
    val archive = stringResource(R.string.badge_archive)
    // "Stream details" off (Settings → Appearance): only what matters to a viewer (audio tracks, subtitles, archive).
    val technical = AppPrefs.streamBadges.collectAsState().value
    val anyLeft = info.audioTrackCount > 1 || info.hasSubtitles || hasArchive
    if (!technical && !anyLeft) return
    Row(modifier.height(24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (technical) {
            info.resolutionLabel?.let { Badge(it, highlighted = true) }
            if (info.height > 0) Badge("${info.width}×${info.height}")
            if (info.frameRate > 0f) Badge("${formatFps(info.frameRate)} fps")
            info.videoCodec?.let { Badge(it) }
            info.audioCodec?.let { codec -> Badge(listOfNotNull(codec, info.channelLayout).joinToString(" ")) }
            if (info.bitrateKbps > 0) Badge(formatBitrate(info.bitrateKbps))
        }
        if (info.audioTrackCount > 1) Badge(audioTracks)
        if (info.hasSubtitles) Badge(subtitles)
        if (hasArchive) Badge(archive)
    }
}

@Composable
private fun Badge(text: String, highlighted: Boolean = false) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
        color = if (highlighted) Color.Black else Color.White.copy(alpha = 0.9f),
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(if (highlighted) Brand.Accent else Color.White.copy(alpha = 0.08f))
            .border(1.dp, if (highlighted) Brand.Accent else Color.White.copy(alpha = 0.25f), shape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun formatFps(fps: Float): String {
    val rounded = fps.roundToInt()
    return if (kotlin.math.abs(fps - rounded) < 0.05f) rounded.toString() else String.format(Locale.getDefault(), "%.2f", fps)
}

private fun formatBitrate(kbps: Int): String =
    if (kbps >= 1000) String.format(Locale.getDefault(), "%.1f Mb/s", kbps / 1000f) else "$kbps kb/s"
