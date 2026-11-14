package rs.timok.player.ui.components

import coil3.request.ImageRequest
import coil3.compose.LocalPlatformContext
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import rs.timok.player.data.settings.AppPrefs
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import rs.timok.player.R
import rs.timok.player.data.db.ChannelRow
import rs.timok.player.ui.theme.Brand

private val logoShape = RoundedCornerShape(6.dp)

@Composable
fun ChannelLogo(url: String?, modifier: Modifier = Modifier) {
    // A shaped background, not a clip: clipping every logo to a rounded shape is costly on TV GPUs.
    Box(modifier.background(Brand.Background.copy(alpha = 0.6f), logoShape), contentAlignment = Alignment.Center) {
        if (url != null) {
            val context = LocalPlatformContext.current
            // Decode at display size: panel logos are often 500+ px and would be uploaded to the GPU at full size.
            val request = remember(url) {
                ImageRequest.Builder(context).data(url).size(LOGO_DECODE_WIDTH, LOGO_DECODE_HEIGHT).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(3.dp),
            )
        }
    }
}

/** Largest logo box in pixels (info bar, 150x100 dp at TV densities); list rows are smaller still. */
private const val LOGO_DECODE_WIDTH = 220
private const val LOGO_DECODE_HEIGHT = 150

/** One channel line: number, logo, name and now/next with progress. */
@Composable
fun ChannelRowContent(row: ChannelRow, now: Long, compact: Boolean = false, playing: Boolean = false) {
    // "Compact list" (Settings → Appearance): lower rows with a smaller logo and no "next" line.
    val dense = AppPrefs.compactList.collectAsState().value
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = if (dense) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            row.number.toString(),
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.End,
            color = if (playing) Brand.Accent else Brand.TextSecondary,
            fontSize = 16.sp,
            fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
        )
        ChannelLogo(row.logo, if (dense) Modifier.size(width = 52.dp, height = 34.dp) else Modifier.size(width = 64.dp, height = 44.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.isFavorite) Text("  ★", color = Brand.Accent, fontSize = 15.sp)
            }
            if (row.nowTitle != null) {
                Text(row.nowTitle, color = Brand.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.nowStart != null && row.nowEnd != null && row.nowEnd > row.nowStart) {
                    ProgramProgress(
                        (now - row.nowStart).toFloat() / (row.nowEnd - row.nowStart),
                        Modifier.fillMaxWidth(0.6f).padding(top = 3.dp),
                    )
                }
                if (!compact && !dense && row.nextTitle != null) {
                    Text(
                        stringResource(R.string.next_program, row.nextTitle),
                        color = Brand.TextSecondary.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (!compact && !dense) {
                Text(stringResource(R.string.no_program_info), color = Brand.TextSecondary.copy(alpha = 0.5f), fontSize = 13.sp)
            }
        }
    }
}

val ChannelRowHeight = 86.dp
private val CompactRowHeight = 60.dp

/** Height of a channel row; follows the "compact list" setting. */
val ChannelRowModifier: Modifier
    @Composable get() = Modifier.fillMaxWidth().height(if (AppPrefs.compactList.collectAsState().value) CompactRowHeight else ChannelRowHeight)
