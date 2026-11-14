package rs.timok.player.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the UI animates (Settings → Appearance → Animations). Off, everything jumps: no scrolling glide,
 * screen transitions, sliding panels or growing focus. Focus stays visible through its outline and color.
 */
val LocalAnimations = staticCompositionLocalOf { true }

/** [spec] when animations are on, an instant jump when off. */
@Composable
@ReadOnlyComposable
fun <T> motion(spec: AnimationSpec<T>): AnimationSpec<T> = if (LocalAnimations.current) spec else snap()

@Composable
@ReadOnlyComposable
fun motion(enter: EnterTransition): EnterTransition = if (LocalAnimations.current) enter else EnterTransition.None

@Composable
@ReadOnlyComposable
fun motion(exit: ExitTransition): ExitTransition = if (LocalAnimations.current) exit else ExitTransition.None

/** Scrolls to [index], gliding only when animations are on. */
suspend fun LazyListState.scrollToItemMotion(index: Int, animate: Boolean) {
    if (animate) animateScrollToItem(index) else scrollToItem(index)
}
