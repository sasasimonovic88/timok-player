package rs.timok.player.ui.theme

import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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

    /** Focus outline and highlights; follows Settings → Appearance → Color (snapshot state, so screens redraw). */
    val Accent: Color get() = accentState.value
    private val accentState = mutableStateOf(accentColor("orange"))

    /** Applied by the theme from the stored choice. */
    fun setAccent(name: String) {
        accentState.value = accentColor(name)
    }

    /** All accents are light enough for the black text drawn on focused items. */
    fun accentColor(name: String): Color = when (name) {
        "blue" -> Color(0xFF4FC3F7)
        "green" -> Color(0xFF66BB6A)
        "purple" -> Color(0xFFBA68C8)
        else -> Color(0xFFFF8F00)
    }

    val Background = Color(0xFF0B1220)
    val Surface = Color(0xFF16213A)
    val SurfaceFocused = Color(0xFF243459)
    val TextPrimary = Color(0xFFF2F5FA)
    val TextSecondary = Color(0xFFA9B4C8)
    val Error = Color(0xFFFF6B6B)
}

private fun colors() = darkColorScheme(
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
    val animations by AppPrefs.animations.collectAsState()
    val accent by AppPrefs.accent.collectAsState()
    // Written only when the choice changes; readers of Brand.Accent then redraw.
    remember(accent) { Brand.setAccent(accent) }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density * scale, density.fontScale),
        // Focus scrolling in every list: a short glide, or a jump when animations are off.
        LocalBringIntoViewSpec provides if (animations) QuickScroll else JumpScroll,
        LocalAnimations provides animations,
    ) {
        MaterialTheme(colorScheme = remember(accent) { colors() }, content = content)
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

/** Animations off: the list jumps straight to the focused item. */
@OptIn(ExperimentalFoundationApi::class)
private val JumpScroll = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = androidx.compose.animation.core.snap()
}
