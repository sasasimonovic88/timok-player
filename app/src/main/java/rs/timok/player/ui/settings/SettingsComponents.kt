package rs.timok.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import rs.timok.player.ui.components.Chip
import rs.timok.player.ui.components.TvClickable
import rs.timok.player.ui.theme.Brand

// Building blocks of the settings pages: rows grouped on cards with a small label above, switches for
// on/off settings, a value with "›" for choices, and a description that shows only on the focused row.

internal val cardShape = RoundedCornerShape(12.dp)
private val cardColor = Color(0xFF131D2E)
private val dividerColor = Color.White.copy(alpha = 0.06f)

/** A labeled card of rows; separate the rows with [GroupDivider]. */
@Composable
internal fun SettingsGroup(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                fontSize = 13.sp,
                color = Brand.TextSecondary,
                letterSpacing = 0.3.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
        }
        Column(Modifier.fillMaxWidth().clip(cardShape).background(cardColor).padding(vertical = 4.dp), content = content)
    }
}

@Composable
internal fun GroupDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(dividerColor))
}

/** Row shell: title, the hint while focused, and [trailing] on the right. No growing focus in dense lists. */
@Composable
private fun RowShell(
    title: String,
    hint: String?,
    onClick: () -> Unit,
    modifier: Modifier,
    titleColor: Color = Brand.TextPrimary,
    trailing: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    TvClickable(
        onClick = onClick,
        focusedScale = 1f,
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp).onFocusChanged { focused = it.isFocused },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (focused && hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Box(Modifier.padding(start = 16.dp)) { trailing() }
        }
    }
}

/** On/off setting with a switch. */
@Composable
internal fun SwitchRow(title: String, checked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, hint: String? = null) {
    RowShell(title, hint, onClick, modifier) { SwitchMark(checked) }
}

@Composable
private fun SwitchMark(checked: Boolean) {
    Box(
        Modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(CircleShape)
            .background(if (checked) Brand.Accent else Color(0xFF2C3949)),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (checked) Brand.Background else Color(0xFF8693A3)),
        )
    }
}

/** A choice or an action: current [value] (may be empty) and "›". [danger] colors the title red. */
@Composable
internal fun ValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    valueActive: Boolean = false,
    danger: Boolean = false,
    chevron: Boolean = true,
) {
    RowShell(title, hint, onClick, modifier, titleColor = if (danger) Brand.Error else Brand.TextPrimary) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value.isNotEmpty()) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (valueActive) Brand.Accent else Brand.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            if (chevron) Text("›", fontSize = 22.sp, color = Brand.TextSecondary.copy(alpha = 0.7f))
        }
    }
}

/** A title (and hint) with a row of chips; the chips themselves take the focus. */
@Composable
internal fun <T> ChipsRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
    hint: String? = null,
    selectedModifier: Modifier = Modifier,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Brand.TextPrimary)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            options.forEach { o ->
                Chip(text = label(o), selected = o == selected, onClick = { onPick(o) }, modifier = if (o == selected) selectedModifier else Modifier)
            }
        }
    }
}

/** Plain label and value, not focusable (account details). */
@Composable
internal fun InfoRow(label: String, value: String, valueColor: Color = Brand.TextPrimary) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Brand.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Short note under a group. */
@Composable
internal fun GroupNote(text: String, color: Color = Brand.TextSecondary) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

/** Standalone row on its own card (category lists, guide checklist). */
@Composable
internal fun SettingRow(
    title: String,
    value: String,
    valueActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    valueSize: TextUnit = TextUnit.Unspecified,
) {
    TvClickable(onClick = onClick, focusedScale = 1f, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.background(cardColor, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
            }
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontSize = valueSize,
                color = if (valueActive) Brand.Accent else Brand.TextSecondary,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
