package rs.timok.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.tv.material3.Text
import rs.timok.player.R
import rs.timok.player.player.AspectMode
import rs.timok.player.player.TrackOption
import rs.timok.player.ui.components.TvClickable
import rs.timok.player.ui.theme.Brand
import java.util.Locale

/** Player options (long-press OK or Menu): favorite, tracks, archive, picture format, sleep timer, reload. */
@Composable
internal fun OptionsPanel(
    title: String,
    /** Null hides the favorite item (VOD). */
    isFavorite: Boolean?,
    visible: Boolean,
    tracks: List<TrackOption>,
    aspect: AspectMode,
    /** Chosen sleep timer duration in minutes; 0 is off. */
    sleepMinutes: Int,
    /** Time left while the sleep timer runs. */
    sleepRemainingMs: Long?,
    onToggleFavorite: () -> Unit,
    onSelectTrack: (TrackOption) -> Unit,
    onSubtitlesOff: () -> Unit,
    onCycleAspect: () -> Unit,
    onCycleSleep: () -> Unit,
    /** Null hides the guide item (VOD). */
    onProgram: (() -> Unit)? = null,
    /** Null hides the archive item (channel without catch-up, VOD). */
    onArchive: (() -> Unit)? = null,
    onReload: () -> Unit,
    /** Null hides the external player item (no player installed). */
    onExternal: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val firstItem = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstItem.requestFocus() }
    }
    val audio = tracks.filter { it.type == C.TRACK_TYPE_AUDIO }
    val subtitles = tracks.filter { it.type == C.TRACK_TYPE_TEXT }
    val aspectLabel = stringResource(aspect.label)

    LazyColumn(
        modifier
            // Focus must not wander out of an open panel: the screen behind ignores keys meanwhile.
            .focusGroup()
            .focusProperties { onExit = { if (visible) cancelFocusChange() } }
            .fillMaxHeight()
            .width(420.dp)
            .padding(vertical = 28.dp, horizontal = 28.dp)
            .clip(panelShape)
            .background(glassTint)
            .background(glassHighlight)
            .border(1.dp, Color.White.copy(alpha = 0.14f), panelShape)
            .padding(vertical = 16.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Text(
                title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )
        }
        if (isFavorite != null) {
            item {
                OptionItem(
                    text = stringResource(if (isFavorite) R.string.options_remove_favorite else R.string.options_add_favorite),
                    leading = "★",
                    leadingColor = if (isFavorite) Brand.Accent else Brand.TextSecondary,
                    onClick = onToggleFavorite,
                    modifier = Modifier.focusRequester(firstItem),
                )
            }
        }
        // A single audio track has nothing to choose from.
        if (audio.size > 1) {
            item { SectionTitle(stringResource(R.string.options_audio)) }
            audio.forEachIndexed { i, t ->
                item(key = "a${t.groupIndex}_${t.trackIndex}") {
                    OptionItem(trackLabel(t, i), checked = t.selected, onClick = { onSelectTrack(t) })
                }
            }
        }
        if (subtitles.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.options_subtitles)) }
            item(key = "s_off") {
                OptionItem(stringResource(R.string.options_subtitles_off), checked = subtitles.none { it.selected }, onClick = onSubtitlesOff)
            }
            subtitles.forEachIndexed { i, t ->
                item(key = "s${t.groupIndex}_${t.trackIndex}") {
                    OptionItem(trackLabel(t, i), checked = t.selected, onClick = { onSelectTrack(t) })
                }
            }
        }
        item { Spacer8() }
        if (onProgram != null) {
            item { OptionItem(stringResource(R.string.options_program), leading = "☰", leadingColor = Brand.Accent, onClick = onProgram) }
        }
        if (onArchive != null) {
            item { OptionItem(stringResource(R.string.options_archive), leading = "⟲", leadingColor = Brand.Accent, onClick = onArchive) }
        }
        item {
            // First focusable item when there is no favorite entry.
            val mod = if (isFavorite == null) Modifier.focusRequester(firstItem) else Modifier
            OptionItem(stringResource(R.string.options_aspect, aspectLabel), leading = "▭", onClick = onCycleAspect, modifier = mod)
        }
        item {
            val value = if (sleepMinutes == 0) stringResource(R.string.sleep_off)
            else stringResource(R.string.sleep_running, sleepMinutes, (((sleepRemainingMs ?: 0) + 59_999) / 60_000).toInt())
            OptionItem(
                stringResource(R.string.options_sleep, value),
                leading = "☾",
                leadingColor = if (sleepMinutes > 0) Brand.Accent else Brand.TextSecondary,
                onClick = onCycleSleep,
            )
        }
        item { OptionItem(stringResource(R.string.options_reload), leading = "↻", onClick = onReload) }
        onExternal?.let { open -> item { OptionItem(stringResource(R.string.external_player_open), leading = "⇱", onClick = open) } }
    }
}

@Composable
private fun trackLabel(t: TrackOption, index: Int): String {
    val language = t.language?.let { code ->
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
            .takeIf { it.isNotBlank() && it != code }
            ?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
            ?: code.uppercase(Locale.ROOT)
    }
    val name = t.label ?: language ?: stringResource(R.string.options_track, index + 1)
    val details = listOfNotNull(t.codec, t.channelLayout).joinToString(" ")
    return if (details.isEmpty()) name else "$name · $details"
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        color = Brand.Accent,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun Spacer8() = Row(Modifier.padding(top = 8.dp)) {}

@Composable
private fun OptionItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    checked: Boolean = false,
    leading: String? = null,
    leadingColor: Color = Brand.TextSecondary,
) {
    TvClickable(onClick = onClick, modifier = modifier.fillMaxWidth(), focusedScale = 1.03f) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (checked) "✓" else leading.orEmpty(),
                color = if (checked) Brand.Accent else leadingColor,
                fontSize = 17.sp,
                modifier = Modifier.width(28.dp),
            )
            Text(text, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
