package rs.timok.player.ui.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import rs.timok.player.ui.components.TvTextField
import rs.timok.player.epg.EpgRepository
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import rs.timok.player.BuildConfig
import rs.timok.player.R
import rs.timok.player.data.settings.AppPrefs
import rs.timok.player.system.LocaleHelper
import rs.timok.player.ui.components.Clock
import rs.timok.player.ui.components.Chip
import rs.timok.player.ui.components.TvButton
import rs.timok.player.ui.components.ConfirmDialog
import rs.timok.player.epg.EpgPresets
import rs.timok.player.ui.components.TvClickable
import rs.timok.player.ui.theme.Brand
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Settings are grouped into tiles; each tile opens its own section. */
private enum class Section(@StringRes val title: Int, @DrawableRes val icon: Int) {
    ACCOUNT(R.string.settings_account, R.drawable.ic_set_account),
    CATEGORIES(R.string.settings_categories, R.drawable.ic_set_categories),
    PLAYBACK(R.string.settings_playback, R.drawable.ic_set_playback),
    INTERFACE(R.string.settings_interface, R.drawable.ic_set_display),
    AUTOSTART(R.string.settings_autostart, R.drawable.ic_set_power),
    BACKUP(R.string.settings_backup, R.drawable.ic_set_backup),
    ABOUT(R.string.settings_about, R.drawable.ic_set_about),
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val signedOut by vm.signedOut.collectAsStateWithLifecycle()
    LaunchedEffect(signedOut) { if (signedOut) onSignedOut() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAutoStartGrant() }

    // Saved so a language change (which recreates the activity) stays in the same section.
    var sectionName by rememberSaveable { mutableStateOf<String?>(null) }
    var lastSectionName by rememberSaveable { mutableStateOf(Section.ACCOUNT.name) }
    val section = sectionName?.let { Section.valueOf(it) }
    BackHandler(enabled = section != null) { sectionName = null }

    val tileFocus = remember { Section.entries.associateWith { FocusRequester() } }
    val sectionFocus = remember { FocusRequester() }
    LaunchedEffect(section) {
        if (section == null) vm.reloadPrefs()
        withFrameNanos { }
        runCatching { if (section == null) tileFocus.getValue(Section.valueOf(lastSectionName)).requestFocus() else sectionFocus.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brand.Background)
            .padding(horizontal = 56.dp, vertical = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (section == null) {
                Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(40.dp))
            } else {
                Icon(painterResource(section.icon), contentDescription = null, tint = Brand.Accent, modifier = Modifier.size(32.dp))
            }
            Text(
                stringResource(section?.title ?: R.string.settings),
                style = MaterialTheme.typography.headlineSmall,
                color = Brand.TextPrimary,
                modifier = Modifier.padding(start = 16.dp).weight(1f),
            )
            Clock(color = Brand.TextSecondary)
        }
        // The tiles' scroll area adds its own top padding (TILE_FOCUS_ROOM).
        Spacer(Modifier.height(if (section == null) 24.dp - TILE_FOCUS_ROOM else 24.dp))

        when (section) {
            null -> Tiles(vm, tileFocus) { s ->
                lastSectionName = s.name
                sectionName = s.name
            }
            Section.ACCOUNT -> AccountSection(vm, sectionFocus)
            Section.CATEGORIES -> CategoriesSection(vm, sectionFocus)
            Section.PLAYBACK -> PlaybackSection(vm, sectionFocus)
            Section.INTERFACE -> InterfaceSection(vm, sectionFocus)
            Section.AUTOSTART -> AutoStartSection(vm, sectionFocus)
            Section.BACKUP -> BackupSection(sectionFocus)
            Section.ABOUT -> AboutSection(sectionFocus)
        }
    }
}

@Composable
private fun Tiles(vm: SettingsViewModel, focus: Map<Section, FocusRequester>, onOpen: (Section) -> Unit) {
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val categories by vm.liveCategories.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val uiScale by vm.uiScale.collectAsStateWithLifecycle()
    val autoStart by vm.autoStart.collectAsStateWithLifecycle()
    val autoStartBlocked by vm.autoStartBlocked.collectAsStateWithLifecycle()

    val summaries = mapOf(
        Section.ACCOUNT to (playlist?.let { stringResource(R.string.settings_status, it.status) } ?: ""),
        Section.CATEGORIES to stringResource(R.string.settings_categories_summary, categories.count { !it.hidden }, categories.size),
        Section.PLAYBACK to stringResource(R.string.settings_playback_summary),
        Section.INTERFACE to "${languageLabel(language)} · ${percent(uiScale)}",
        Section.AUTOSTART to stringResource(if (autoStart) R.string.state_on else R.string.state_off),
        Section.BACKUP to stringResource(R.string.settings_backup_summary),
        Section.ABOUT to stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
    )
    // Scrolls when a large display size pushes the last row off screen; focus brings tiles into view.
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        // The scroll area clips its content: inner padding leaves room for the focused tile's scale and outline
        // (the header spacer above is reduced by the same amount, so the tiles stay where they were).
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(vertical = TILE_FOCUS_ROOM),
    ) {
        Section.entries.chunked(TILES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { s ->
                    Tile(
                        section = s,
                        summary = summaries.getValue(s),
                        warning = s == Section.AUTOSTART && autoStartBlocked,
                        onClick = { onOpen(s) },
                        modifier = Modifier.weight(1f).focusRequester(focus.getValue(s)),
                    )
                }
                // Keep tiles of a short last row as wide as the others.
                repeat(TILES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val TILES_PER_ROW = 3

/** Same corners as the focus outline of [rs.timok.player.ui.components.TvClickable]. */
private val tileShape = RoundedCornerShape(8.dp)

@Composable
private fun Tile(section: Section, summary: String, warning: Boolean, onClick: () -> Unit, modifier: Modifier) {
    // Compact: icon beside the text, so all tiles fit on one screen even at 100% display size.
    TvClickable(onClick = onClick, modifier = modifier.height(TILE_HEIGHT), focusedScale = 1.04f) {
        Row(
            Modifier.fillMaxSize().background(Brand.Surface.copy(alpha = 0.6f), tileShape).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(section.icon), contentDescription = null, tint = Brand.Accent, modifier = Modifier.size(30.dp))
            Column(Modifier.padding(start = 16.dp)) {
                Text(stringResource(section.title), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (warning) "⚠ $summary" else summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warning) Brand.Accent else Brand.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val TILE_HEIGHT = 76.dp

/** Space the focused tile grows into (scale + outline) inside the clipping scroll area. */
private val TILE_FOCUS_ROOM = 8.dp

@Composable
private fun AccountSection(vm: SettingsViewModel, focus: FocusRequester) {
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val never = stringResource(R.string.settings_never)
    fun fmt(t: Long?) = t?.let { dateFormat.format(Date(it)) } ?: never

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        playlist?.let { p ->
            Info(p.name)
            Info(stringResource(R.string.settings_status, p.status))
            Info(p.expiresAt?.let { stringResource(R.string.settings_expires, fmt(it)) } ?: stringResource(R.string.settings_expires_never))
            Info(stringResource(R.string.settings_connections, p.maxConnections))
            Info(stringResource(R.string.settings_last_refresh, fmt(p.channelsUpdatedAt)))
            Info(stringResource(R.string.settings_epg_updated, fmt(p.epgUpdatedAt)))
            p.epgError?.let { Text(epgErrorText(it), color = Brand.Error) }
            // Checked against the server when the keyboard closes; a bad address puts the old one back.
            var server by remember(p.id, p.server) { mutableStateOf(p.server) }
            val scope = rememberCoroutineScope()
            TvTextField(
                value = server,
                onValueChange = { server = it },
                label = stringResource(R.string.settings_server),
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                onImeAction = {
                    scope.launch { if (!vm.changeServer(server)) server = p.server }
                },
                modifier = Modifier.width(760.dp).padding(top = 8.dp),
            )
            Text(stringResource(R.string.settings_server_hint), color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
            // Field state survives typing; it is saved when the keyboard closes.
            var epgUrl by remember(p.id) { mutableStateOf(p.epgUrl.orEmpty()) }
            TvTextField(
                value = epgUrl,
                onValueChange = { epgUrl = it },
                label = stringResource(R.string.settings_epg_url),
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                onImeAction = { vm.setEpgUrl(epgUrl) },
                modifier = Modifier.width(760.dp).padding(top = 8.dp),
            )
            Text(stringResource(R.string.settings_epg_url_hint), color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
            val presets by vm.epgPresets.collectAsStateWithLifecycle()
            var choosePresets by remember { mutableStateOf(false) }
            val locale = LocalConfiguration.current.locales[0]
            val labels = remember(locale) { EpgPresets.labels(locale) }
            SettingRow(
                title = stringResource(R.string.settings_epg_presets),
                value = EpgPresets.all.filter { it.id in presets }.map { labels.getValue(it.id) }.let { names ->
                    // A long choice would push the title out of the row.
                    names.take(3).joinToString(", ") + if (names.size > 3) " +${names.size - 3}" else ""
                }.ifEmpty { stringResource(R.string.settings_epg_presets_none) },
                valueActive = presets.isNotEmpty(),
                onClick = { choosePresets = true },
                modifier = Modifier.width(760.dp).padding(top = 8.dp),
            )
            if (choosePresets) {
                EpgPresetsDialog(
                    selected = presets,
                    labels = labels,
                    onDone = { chosen ->
                        choosePresets = false
                        vm.setEpgPresets(chosen)
                    },
                )
            }
            Text(stringResource(R.string.settings_epg_presets_hint), color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        status?.let { Text(it, color = Brand.TextSecondary) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 16.dp)) {
            TvButton(stringResource(R.string.settings_refresh), onClick = vm::refresh, modifier = Modifier.focusRequester(focus))
            TvButton(stringResource(R.string.settings_logout), onClick = { confirmRemove = true })
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(R.string.remove_account_title),
            message = stringResource(R.string.remove_account_message),
            confirmLabel = stringResource(R.string.settings_logout),
            onConfirm = {
                confirmRemove = false
                vm.removeAccount()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun epgErrorText(error: String): String = when {
    error == EpgRepository.NO_MATCH -> stringResource(R.string.settings_epg_no_match)
    error.startsWith("EPG HTTP ") -> stringResource(R.string.settings_epg_http, error.removePrefix("EPG HTTP "))
    else -> stringResource(R.string.settings_epg_failed)
}

@Composable
private fun CategoriesSection(vm: SettingsViewModel, focus: FocusRequester) {
    val tab by vm.categoryTab.collectAsStateWithLifecycle()
    val state by vm.categories.collectAsStateWithLifecycle()
    val tabs = listOf(
        CategoryTab.LIVE to R.string.nav_tv,
        CategoryTab.MOVIES to R.string.nav_movies,
        CategoryTab.SERIES to R.string.nav_series,
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { (t, label) ->
                Chip(
                    text = stringResource(label),
                    selected = t == tab,
                    onClick = { vm.selectCategoryTab(t) },
                    modifier = if (t == tab) Modifier.focusRequester(focus) else Modifier,
                )
            }
            Spacer(Modifier.width(24.dp))
            TvButton(stringResource(R.string.settings_show_all), onClick = { vm.setAllCategoriesHidden(false) })
            TvButton(stringResource(R.string.settings_hide_all), onClick = { vm.setAllCategoriesHidden(true) })
        }
        Text(stringResource(if (tab == CategoryTab.LIVE) R.string.settings_categories_hint else R.string.settings_categories_hint_vod), color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
        when (val st = state) {
            CategoriesState.Loading -> Text(stringResource(R.string.vod_loading), color = Brand.TextSecondary)
            is CategoriesState.Error -> Text(st.message, color = Brand.Error)
            is CategoriesState.Ready -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(st.items, key = { it.key }) { item ->
                    SettingRow(
                        title = item.name,
                        value = stringResource(if (item.hidden) R.string.category_hidden else R.string.category_shown),
                        valueActive = !item.hidden,
                        onClick = { vm.toggleCategory(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackSection(vm: SettingsViewModel, focus: FocusRequester) {
    val backgroundPlayback by vm.backgroundPlayback.collectAsStateWithLifecycle()
    val limitVideo by vm.limitVideo.collectAsStateWithLifecycle()
    val secureDns by vm.secureDns.collectAsStateWithLifecycle()
    val textureVideo by vm.textureVideo.collectAsStateWithLifecycle()
    Column(Modifier.width(760.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingRow(
            title = stringResource(R.string.settings_background_video),
            hint = stringResource(R.string.settings_background_video_hint),
            value = onOff(backgroundPlayback),
            valueActive = backgroundPlayback,
            onClick = vm::toggleBackgroundPlayback,
            modifier = Modifier.focusRequester(focus),
        )
        SettingRow(
            title = stringResource(R.string.settings_limit_video),
            hint = stringResource(R.string.settings_limit_video_hint),
            value = onOff(limitVideo),
            valueActive = limitVideo,
            onClick = vm::toggleLimitVideo,
        )
        SettingRow(
            title = stringResource(R.string.settings_texture_video),
            hint = stringResource(R.string.settings_texture_video_hint),
            value = onOff(textureVideo),
            valueActive = textureVideo,
            onClick = vm::toggleTextureVideo,
        )
        SettingRow(
            title = stringResource(R.string.settings_secure_dns),
            hint = stringResource(R.string.settings_secure_dns_hint),
            value = onOff(secureDns),
            valueActive = secureDns,
            onClick = vm::toggleSecureDns,
        )
    }
}

@Composable
private fun InterfaceSection(vm: SettingsViewModel, focus: FocusRequester) {
    val language by vm.language.collectAsStateWithLifecycle()
    val uiScale by vm.uiScale.collectAsStateWithLifecycle()
    val animations by vm.animations.collectAsStateWithLifecycle()
    val panelTimeout by vm.panelTimeout.collectAsStateWithLifecycle()
    val listFullscreen by vm.listFullscreen.collectAsStateWithLifecycle()
    val compactList by vm.compactList.collectAsStateWithLifecycle()
    val streamBadges by vm.streamBadges.collectAsStateWithLifecycle()
    val clockOverlay by vm.clockOverlay.collectAsStateWithLifecycle()
    val accent by vm.accent.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    Column(Modifier.width(560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_display_size), style = MaterialTheme.typography.titleMedium, color = Brand.TextPrimary)
        Text(stringResource(R.string.settings_display_size_hint), style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            AppPrefs.uiScales.forEach { scale ->
                val selected = scale == uiScale
                Chip(
                    text = percent(scale),
                    selected = selected,
                    onClick = { vm.setUiScale(scale) },
                    modifier = if (selected) Modifier.focusRequester(focus) else Modifier,
                )
            }
        }
        SettingRow(
            title = stringResource(R.string.settings_animations),
            hint = stringResource(R.string.settings_animations_hint),
            value = onOff(animations),
            valueActive = animations,
            onClick = vm::toggleAnimations,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            stringResource(R.string.settings_accent),
            style = MaterialTheme.typography.titleMedium,
            color = Brand.TextPrimary,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            AppPrefs.accents.forEach { name ->
                Chip(
                    text = "● " + stringResource(
                        when (name) {
                            "blue" -> R.string.accent_blue
                            "green" -> R.string.accent_green
                            "purple" -> R.string.accent_purple
                            else -> R.string.accent_orange
                        }
                    ),
                    selected = name == accent,
                    onClick = { vm.setAccent(name) },
                )
            }
        }
        SettingRow(
            title = stringResource(R.string.settings_compact_list),
            hint = stringResource(R.string.settings_compact_list_hint),
            value = onOff(compactList),
            valueActive = compactList,
            onClick = vm::toggleCompactList,
            modifier = Modifier.padding(top = 8.dp),
        )
        SettingRow(
            title = stringResource(R.string.settings_stream_badges),
            hint = stringResource(R.string.settings_stream_badges_hint),
            value = onOff(streamBadges),
            valueActive = streamBadges,
            onClick = vm::toggleStreamBadges,
        )
        SettingRow(
            title = stringResource(R.string.settings_clock_overlay),
            hint = stringResource(R.string.settings_clock_overlay_hint),
            value = onOff(clockOverlay),
            valueActive = clockOverlay,
            onClick = vm::toggleClockOverlay,
        )
        Text(
            stringResource(R.string.settings_panel_timeout),
            style = MaterialTheme.typography.titleMedium,
            color = Brand.TextPrimary,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(stringResource(R.string.settings_panel_timeout_hint), style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            AppPrefs.panelTimeouts.forEach { seconds ->
                Chip(
                    text = stringResource(R.string.settings_seconds, seconds),
                    selected = seconds == panelTimeout,
                    onClick = { vm.setPanelTimeout(seconds) },
                )
            }
        }
        Text(
            stringResource(R.string.settings_list_fullscreen),
            style = MaterialTheme.typography.titleMedium,
            color = Brand.TextPrimary,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(stringResource(R.string.settings_list_fullscreen_hint), style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            AppPrefs.listFullscreenDelays.forEach { seconds ->
                Chip(
                    text = when {
                        seconds == 0 -> stringResource(R.string.settings_never_option)
                        seconds >= 60 -> stringResource(R.string.settings_minutes, seconds / 60)
                        else -> stringResource(R.string.settings_seconds, seconds)
                    },
                    selected = seconds == listFullscreen,
                    onClick = { vm.setListFullscreen(seconds) },
                )
            }
        }
        Text(
            stringResource(R.string.settings_language),
            style = MaterialTheme.typography.titleMedium,
            color = Brand.TextPrimary,
            modifier = Modifier.padding(top = 12.dp),
        )
        AppPrefs.languages.forEach { tag ->
            val selected = tag == language
            SettingRow(
                title = languageLabel(tag),
                value = if (selected) "✓" else "",
                valueActive = true,
                onClick = {
                    if (!selected) {
                        vm.setLanguage(tag)
                        // Activity strings are resolved at attach time, so rebuild it in the new language.
                        activity?.recreate()
                    }
                },
            )
        }
    }
}

@Composable
private fun AutoStartSection(vm: SettingsViewModel, focus: FocusRequester) {
    val autoStart by vm.autoStart.collectAsStateWithLifecycle()
    val blocked by vm.autoStartBlocked.collectAsStateWithLifecycle()
    val noGrantScreen by vm.noGrantScreen.collectAsStateWithLifecycle()
    Column(Modifier.width(760.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingRow(
            title = stringResource(R.string.settings_autostart),
            hint = stringResource(R.string.settings_autostart_hint),
            value = onOff(autoStart),
            valueActive = autoStart,
            onClick = vm::toggleAutoStart,
            modifier = Modifier.focusRequester(focus),
        )
        if (blocked) {
            Text(stringResource(R.string.settings_autostart_permission), color = Brand.Accent, style = MaterialTheme.typography.bodyMedium)
            TvButton(stringResource(R.string.settings_autostart_grant), onClick = vm::requestAutoStartGrant)
            if (noGrantScreen) {
                Text(stringResource(R.string.settings_autostart_no_screen), color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Full-width focusable row: title (and optional hint) on the left, current value on the right. */
/** Checklist of the built-in guides; the choice is applied (and the guide reloaded) when the dialog closes. */
@Composable
private fun EpgPresetsDialog(selected: Set<String>, labels: Map<String, String>, onDone: (Set<String>) -> Unit) {
    var chosen by remember { mutableStateOf(selected) }
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = { onDone(chosen) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(620.dp)
                .fillMaxHeight(0.9f)
                .background(Brand.Background, tileShape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.settings_epg_presets), style = MaterialTheme.typography.titleLarge, color = Brand.TextPrimary)
            Text(stringResource(R.string.settings_epg_presets_choose_hint), style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(4.dp),
            ) {
                items(EpgPresets.all, key = { it.id }) { preset ->
                    val checked = preset.id in chosen
                    SettingRow(
                        title = labels.getValue(preset.id),
                        value = if (checked) "☑" else "☐",
                        valueActive = checked,
                        onClick = { chosen = if (checked) chosen - preset.id else chosen + preset.id },
                        modifier = if (preset == EpgPresets.all.first()) Modifier.focusRequester(first) else Modifier,
                        valueSize = 26.sp,
                    )
                }
            }
            TvButton(stringResource(R.string.settings_epg_presets_done), onClick = { onDone(chosen) })
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

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
    TvClickable(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.background(Brand.Surface.copy(alpha = 0.6f), tileShape).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
                }
            }
            Box(Modifier.padding(start = 16.dp)) {
                Text(
                    value,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = valueSize,
                    color = if (valueActive) Brand.Accent else Brand.TextSecondary,
                )
            }
        }
    }
}

private fun percent(scale: Float) = "${(scale * 100).roundToInt()}%"

@Composable
private fun onOff(on: Boolean) = stringResource(if (on) R.string.state_on else R.string.state_off)

@Composable
private fun languageLabel(tag: String): String =
    if (tag.isEmpty()) {
        val system = android.content.res.Resources.getSystem().configuration.let {
            if (android.os.Build.VERSION.SDK_INT >= 24) it.locales[0] else @Suppress("DEPRECATION") it.locale
        }
        // Native names for our languages (Latin Serbian), the system's own name otherwise.
        val name = LocaleHelper.displayName(system.language).ifEmpty {
            system.getDisplayLanguage(system).replaceFirstChar { it.titlecase(system) }
        }
        stringResource(R.string.settings_language_system, name)
    } else LocaleHelper.displayName(tag)

@Composable
private fun Info(text: String) {
    Text(text, color = Brand.TextPrimary, style = MaterialTheme.typography.bodyLarge)
}
