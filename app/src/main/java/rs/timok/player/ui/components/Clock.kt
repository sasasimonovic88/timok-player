package rs.timok.player.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.background

@Composable
fun Clock(modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    val context = LocalContext.current
    val fmt = remember { android.text.format.DateFormat.getTimeFormat(context) }
    Text(fmt.format(Date(now)), modifier = modifier, color = color, style = MaterialTheme.typography.titleMedium)
}

/** Formats an epoch time as a short local time (respects the device 12/24h setting). */
@Composable
fun rememberTimeFormat(): DateFormat {
    val context = LocalContext.current
    return remember { android.text.format.DateFormat.getTimeFormat(context) }
}

/**
 * Small clock in the top corner while watching (Settings → Appearance → Clock while watching), like a cable box.
 * A dark pill keeps it readable over bright pictures. [hidden] makes room for things drawn in the same corner.
 */
@Composable
fun BoxScope.WatchClock(hidden: Boolean = false) {
    val enabled by rs.timok.player.data.settings.AppPrefs.clockOverlay.collectAsState()
    if (!enabled || hidden) return
    Clock(
        color = Color.White,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 24.dp, end = 32.dp)
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
