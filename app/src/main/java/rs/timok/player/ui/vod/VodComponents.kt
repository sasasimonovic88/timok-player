package rs.timok.player.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import rs.timok.player.ui.components.ProgramProgress
import rs.timok.player.ui.theme.Brand

/** 2:3 poster with a fallback title when the image is missing. */
@Composable
fun Poster(url: String?, title: String, modifier: Modifier = Modifier, progress: Float? = null) {
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(8.dp))
            .background(Brand.Surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            fontSize = 14.sp,
            color = Brand.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(8.dp),
        )
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (progress != null) {
            Column(Modifier.fillMaxSize(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom) {
                ProgramProgress(progress, Modifier.fillMaxWidth().padding(6.dp))
            }
        }
    }
}

/** "1:23:45" / "12:34". */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
