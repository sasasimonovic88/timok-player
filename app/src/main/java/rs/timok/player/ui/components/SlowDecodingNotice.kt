package rs.timok.player.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import rs.timok.player.R

/**
 * Tells the viewer once per item that this device cannot decode the video smoothly (e.g. 4K HEVC 10-bit on a
 * weak box) and suggests an HD version. A toast does not cover the picture or take focus from the player.
 */
@Composable
fun SlowDecodingNotice(slow: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(slow) {
        if (!slow) return@LaunchedEffect
        // Shown twice: a single long toast (3.5 s) is easy to miss on a TV.
        repeat(2) {
            Toast.makeText(context, R.string.player_slow_decoding, Toast.LENGTH_LONG).show()
            delay(3_600)
        }
    }
}
