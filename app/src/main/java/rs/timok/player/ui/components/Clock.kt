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
