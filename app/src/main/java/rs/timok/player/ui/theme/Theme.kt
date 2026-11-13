package rs.timok.player.ui.theme

import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import rs.timok.player.data.settings.AppPrefs
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

// TIM-OK Digital placeholder palette; keep in sync with res/values/colors.xml.
object Brand {
    val Primary = Color(0xFF1565C0)
    val Accent = Color(0xFFFF8F00)
    val Background = Color(0xFF0B1220)
    val Surface = Color(0xFF16213A)
    val SurfaceFocused = Color(0xFF243459)
    val TextPrimary = Color(0xFFF2F5FA)
    val TextSecondary = Color(0xFFA9B4C8)
    val Error = Color(0xFFFF6B6B)
}

private val colors = darkColorScheme(
    primary = Brand.Primary,
    onPrimary = Color.White,
    secondary = Brand.Accent,
    onSecondary = Color.Black,
    background = Brand.Background,
    onBackground = Brand.TextPrimary,
    surface = Brand.Surface,
    onSurface = Brand.TextPrimary,
    surfaceVariant = Brand.SurfaceFocused,
    onSurfaceVariant = Brand.TextSecondary,
    error = Brand.Error,
    border = Brand.Accent,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimokTheme(content: @Composable () -> Unit) {
    // "Display size" shrinks dp and sp alike, so layouts keep their proportions while taking less screen.
    val scale by AppPrefs.uiScale.collectAsState()
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density * scale, density.fontScale),
        LocalBringIntoViewSpec provides QuickScroll,
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

/**
 * D-pad scrolling follows focus with a short tween instead of the default spring: every frame is expensive on
 * weak TV boxes, and fewer animation frames per key press feel snappier there.
 */
@OptIn(ExperimentalFoundationApi::class)
private val QuickScroll = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(durationMillis = 120, easing = FastOutSlowInEasing)
}
