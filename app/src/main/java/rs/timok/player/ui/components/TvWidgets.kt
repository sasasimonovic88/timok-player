package rs.timok.player.ui.components

import androidx.tv.material3.LocalContentColor
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import rs.timok.player.ui.theme.Brand

private val itemShape = RoundedCornerShape(8.dp)

/**
 * Shared focus styling: accent border and slight scale, so focus is always visible from the couch.
 *
 * Drawn by hand instead of with tv-material's Surface: Surface clips its content to the rounded shape, and
 * rounded clipping of every row is what makes lists stutter on weak TV GPUs (Mali-400 class). Here the rounded
 * background and border are only drawn; nothing is clipped.
 */
@Composable
fun TvClickable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    highlighted: Boolean = false,
    focusedScale: Float = 1.02f,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // A long press already acted; its key-up must not also click.
    var longPressed by remember { mutableStateOf(false) }
    // Animations off: no growing focus at all; the outline and color still show it.
    val animate = rs.timok.player.ui.theme.LocalAnimations.current
    val scale by animateFloatAsState(
        if (focused && animate) focusedScale else 1f,
        rs.timok.player.ui.theme.motion(tween(FOCUS_ANIM_MS)),
        label = "focusScale",
    )
    val container = when {
        focused && highlighted -> Brand.Accent.copy(alpha = 0.45f)
        focused -> Brand.SurfaceFocused
        highlighted -> Brand.Accent.copy(alpha = 0.25f)
        selected -> Brand.Primary.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                val radius = CornerRadius(ITEM_RADIUS.toPx())
                if (container.alpha > 0f) drawRoundRect(container, cornerRadius = radius)
                if (focused) {
                    val stroke = FOCUS_BORDER.toPx()
                    drawRoundRect(
                        Brand.Accent,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = radius,
                        style = Stroke(stroke),
                    )
                }
            }
            .onPreviewKeyEvent { e ->
                if (onLongClick == null || !e.nativeKeyEvent.keyCode.isSelectKey()) return@onPreviewKeyEvent false
                when {
                    e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> {
                        longPressed = false
                        false
                    }
                    e.type == KeyEventType.KeyDown && e.nativeKeyEvent.isLongPress -> {
                        longPressed = true
                        onLongClick()
                        true
                    }
                    // Swallow the rest of a long press (repeats and key-up) so it does not click as well.
                    else -> longPressed
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick),
    ) {
        CompositionLocalProvider(LocalContentColor provides if (focused) Color.White else Brand.TextPrimary) {
            content()
        }
    }
}

private val ITEM_RADIUS = 8.dp
private val FOCUS_BORDER = 2.dp
private const val FOCUS_ANIM_MS = 120

private fun Int.isSelectKey() =
    this == KeyEvent.KEYCODE_DPAD_CENTER || this == KeyEvent.KEYCODE_ENTER || this == KeyEvent.KEYCODE_NUMPAD_ENTER

/**
 * Standard button. Tracks its own focus state: the tv-material Surface did not always show focus that was
 * given programmatically while a screen opened (the button had focus, but looked unfocused).
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: ButtonStyle = ButtonStyle.Primary,
) {
    var focused by remember { mutableStateOf(false) }
    val animate = rs.timok.player.ui.theme.LocalAnimations.current
    val scale by animateFloatAsState(
        if (focused && animate) 1.05f else 1f,
        rs.timok.player.ui.theme.motion(tween(FOCUS_ANIM_MS)),
        label = "buttonScale",
    )
    val shape = itemShape
    Box(
        modifier
            .height(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    !enabled -> Brand.Surface
                    focused && style == ButtonStyle.Danger -> Brand.Error
                    focused -> Brand.Accent
                    style == ButtonStyle.Primary -> Brand.Primary
                    else -> Brand.SurfaceFocused
                },
                shape,
            )
            // Stays focusable while disabled (e.g. while signing in), so focus does not jump away from it.
            .clickable(interactionSource = null, indication = null) { if (enabled) onClick() }
            .padding(horizontal = 28.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                focused && enabled -> Color.Black
                style == ButtonStyle.Danger -> Brand.Error
                else -> Color.White
            },
        )
    }
}

/** Primary: the screen's main action (blue at rest). Neutral: everything else. Danger: removes or turns off. */
enum class ButtonStyle { Primary, Neutral, Danger }

/**
 * Text input that is safe for D-pad: moving focus over it does not pop the keyboard.
 * OK on the field starts editing; the IME action ends it.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    /** Opens the keyboard right away, e.g. on the search screen. */
    startEditing: Boolean = false,
    /**
     * Also run [onImeAction] when the field is left with the D-pad, not only on the keyboard's Done key.
     * For settings fields: viewers often close the keyboard and move on without pressing Done.
     */
    commitOnLeave: Boolean = false,
) {
    var editing by remember { mutableStateOf(startEditing) }
    val fieldFocus = remember { FocusRequester() }
    val surfaceFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Own text state so the cursor can be put at the end: TV keyboards cannot move it, and a cursor at the
    // start makes typing prepend to the old text and Backspace delete nothing.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    LaunchedEffect(editing) {
        if (editing) {
            field = field.copy(selection = TextRange(field.text.length))
            fieldFocus.requestFocus()
        }
    }
    var dirty by remember { mutableStateOf(false) }
    fun leave() {
        editing = false
        if (commitOnLeave && dirty) {
            dirty = false
            onImeAction()
        }
    }

    Surface(
        onClick = { editing = true },
        modifier = modifier.fillMaxWidth().focusRequester(surfaceFocus),
        shape = ClickableSurfaceDefaults.shape(itemShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Brand.Surface,
            focusedContainerColor = Brand.SurfaceFocused,
            contentColor = Brand.TextPrimary,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(2.dp, if (editing) Brand.Accent else Color.Transparent), shape = itemShape),
            focusedBorder = Border(BorderStroke(2.dp, Brand.Accent), shape = itemShape),
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(label, color = Brand.TextSecondary, fontSize = 13.sp)
            BasicTextField(
                value = field,
                onValueChange = {
                    if (it.text != field.text) dirty = true
                    field = it
                    if (it.text != value) onValueChange(it.text)
                },
                singleLine = true,
                textStyle = TextStyle(color = Brand.TextPrimary, fontSize = 20.sp),
                cursorBrush = SolidColor(Brand.Accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    imeAction = imeAction,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onAny = {
                    editing = false
                    dirty = false
                    surfaceFocus.requestFocus()
                    onImeAction()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .focusRequester(fieldFocus)
                    // With the keyboard closed by Back the field keeps focus; up/down then leave it
                    // instead of being swallowed, so the results below stay reachable.
                    .onPreviewKeyEvent { event ->
                        val direction = when (event.key) {
                            Key.DirectionDown -> FocusDirection.Down
                            Key.DirectionUp -> FocusDirection.Up
                            else -> return@onPreviewKeyEvent false
                        }
                        if (event.type == KeyEventType.KeyDown) {
                            leave()
                            if (!focusManager.moveFocus(direction)) surfaceFocus.requestFocus()
                        }
                        true
                    }
                    .focusProperties { canFocus = editing }
                    .onFocusChanged { if (!it.isFocused && editing) leave() },
            )
        }
    }
}

/** Thin progress bar for the current programme; drawn, not clipped (see [TvClickable]). */
@Composable
fun ProgramProgress(fraction: Float, modifier: Modifier = Modifier) {
    val track = Brand.TextSecondary.copy(alpha = 0.3f)
    Box(
        modifier.height(3.dp).drawBehind {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = radius)
            val filled = size.width * fraction.coerceIn(0f, 1f)
            if (filled > 0f) drawRoundRect(Brand.Accent, size = Size(filled, size.height), cornerRadius = radius)
        },
    )
}

/** Small selectable pill used for tabs and option steps. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvClickable(onClick = onClick, selected = selected, modifier = modifier, focusedScale = 1.05f) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) Brand.Accent else Brand.TextPrimary,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        )
    }
}
