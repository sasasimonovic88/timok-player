package rs.timok.player.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import rs.timok.player.BuildConfig
import rs.timok.player.R
import rs.timok.player.data.settings.AppPrefs
import rs.timok.player.epg.EpgPresets
import rs.timok.player.epg.EpgRepository
import rs.timok.player.system.LocaleHelper
import rs.timok.player.ui.components.ButtonStyle
import rs.timok.player.ui.components.Chip
import rs.timok.player.ui.components.Clock
import rs.timok.player.ui.components.ConfirmDialog
import rs.timok.player.ui.components.TvButton
import rs.timok.player.ui.components.TvClickable
import rs.timok.player.ui.components.TvTextField
import rs.timok.player.ui.theme.Brand
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Settings sections, listed in the menu on the left. */
private enum class Section(@StringRes val title: Int, @DrawableRes val icon: Int) {
    ACCOUNT(R.string.settings_account, R.drawable.ic_set_account),
    CATEGORIES(R.string.settings_categories, R.drawable.ic_set_categories),
    PARENTAL(R.string.settings_parental, R.drawable.ic_set_lock),
    PLAYBACK(R.string.settings_playback, R.drawable.ic_set_playback),
    INTERFACE(R.string.settings_interface, R.drawable.ic_set_display),
    AUTOSTART(R.string.settings_autostart, R.drawable.ic_set_power),
    BACKUP(R.string.settings_backup, R.drawable.ic_set_backup),
    ABOUT(R.string.settings_about, R.drawable.ic_set_about),
}

/** Same corners as the focus outline of [TvClickable]; used by the dialogs. */
private val tileShape = RoundedCornerShape(8.dp)

private val navColor = Color(0xFF0E1726)

/**
 * Set while a row that is about to disappear has focus (the PIN row, "Set PIN", "Turn off"). When it goes, the
 * window gives focus to the first menu item; while held, that must not switch the section.
 */
private class NavHold {
    var active by mutableStateOf(false)
}

/**
 * Two panes: the section menu on the left, the focused section's settings on the right (they follow the menu's
 * focus, nothing to open). Right goes into the settings, Left or Back returns to the menu, Back there leaves.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    onAddAccount: () -> Unit = {},
    onSwitchAccount: (Long) -> Unit = {},
    /** The active account was removed and another stored one took its place. */
    onAccountChanged: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val signedOut by vm.signedOut.collectAsStateWithLifecycle()
    LaunchedEffect(signedOut) { if (signedOut) onSignedOut() }
    val switchedToOther by vm.switchedToOther.collectAsStateWithLifecycle()
    LaunchedEffect(switchedToOther) { if (switchedToOther) onAccountChanged() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAutoStartGrant() }

    // Saved so a language change (which recreates the activity) stays in the same section.
    var sectionName by rememberSaveable { mutableStateOf(Section.ACCOUNT.name) }
    val section = Section.valueOf(sectionName)
    val navFocus = remember { Section.entries.associateWith { FocusRequester() } }
    val contentFocus = remember { FocusRequester() }
    val hold = remember { NavHold() }
    var contentFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = contentFocused) { runCatching { navFocus.getValue(section).requestFocus() } }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { navFocus.getValue(section).requestFocus() }
    }
    // Another screen may have changed settings (e.g. a restored backup).
    LaunchedEffect(section) { vm.reloadPrefs() }

    Row(Modifier.fillMaxSize().background(Brand.Background)) {
        NavPane(vm, section, navFocus, contentFocus, onFocus = { if (!hold.active) sectionName = it.name })
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 36.dp, end = 48.dp, top = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                Text(
                    stringResource(section.title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Brand.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Clock(color = Brand.TextSecondary)
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onFocusChanged { contentFocused = it.hasFocus }
                    // Left out of the settings goes back to this section's menu item, not to whichever is beside it.
                    .focusProperties {
                        onExit = {
                            if (requestedFocusDirection == FocusDirection.Left) {
                                cancelFocusChange()
                                runCatching { navFocus.getValue(section).requestFocus() }
                            }
                        }
                    }
                    .focusGroup(),
            ) {
                key(section) {
                    when (section) {
                        Section.ACCOUNT -> AccountSection(vm, contentFocus, onAddAccount, onSwitchAccount)
                        Section.CATEGORIES -> CategoriesSection(vm, contentFocus)
                        Section.PARENTAL -> PinGate(vm, contentFocus, hold) { ParentalSection(vm, contentFocus, hold) }
                        Section.PLAYBACK -> PlaybackSection(vm, contentFocus)
                        Section.INTERFACE -> InterfaceSection(vm, contentFocus)
                        Section.AUTOSTART -> AutoStartSection(vm, contentFocus)
                        // A restore could unlock categories, so it is behind the PIN as well.
                        Section.BACKUP -> PinGate(vm, contentFocus, hold) { BackupSection(contentFocus) }
                        Section.ABOUT -> AboutSection(contentFocus)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavPane(
    vm: SettingsViewModel,
    current: Section,
    navFocus: Map<Section, FocusRequester>,
    contentFocus: FocusRequester,
    onFocus: (Section) -> Unit,
) {
    val autoStartBlocked by vm.autoStartBlocked.collectAsStateWithLifecycle()
    Column(
        Modifier
            .width(290.dp)
            .fillMaxHeight()
            .background(navColor)
            .padding(horizontal = 14.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, bottom = 20.dp)) {
            Icon(painterResource(R.drawable.ic_nav_settings), contentDescription = null, tint = Brand.Accent, modifier = Modifier.size(26.dp))
            Text(
                stringResource(R.string.settings),
                style = MaterialTheme.typography.titleLarge,
                color = Brand.TextPrimary,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Section.entries.forEach { s ->
                NavItem(
                    section = s,
                    selected = s == current,
                    warning = s == Section.AUTOSTART && autoStartBlocked,
                    modifier = Modifier
                        .focusRequester(navFocus.getValue(s))
                        .focusProperties { right = contentFocus }
                        .onFocusChanged { if (it.isFocused) onFocus(s) },
                    onClick = { runCatching { contentFocus.requestFocus() } },
                )
            }
        }
        // Brand and version at the bottom of the menu.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, top = 16.dp)) {
            Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(40.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.titleSmall, color = Brand.TextPrimary)
                Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
            }
        }
    }
}

@Composable
private fun NavItem(section: Section, selected: Boolean, warning: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // Focused: the usual outline. Selected while the settings on the right have focus: an accent tint.
    val tint = selected && !focused
    TvClickable(onClick = onClick, focusedScale = 1f, modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (tint) Brand.Accent.copy(alpha = 0.16f) else Color.Transparent, tileShape)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(section.icon),
                contentDescription = null,
                tint = if (selected || focused) Brand.Accent else Brand.TextSecondary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                stringResource(section.title) + if (warning) "  ⚠" else "",
                style = MaterialTheme.typography.titleMedium,
                color = if (tint) Brand.Accent else if (focused) Brand.TextPrimary else Brand.TextSecondary,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp).weight(1f),
            )
            if (tint) Text("›", fontSize = 20.sp, color = Brand.Accent)
        }
    }
}

/** Scrolling column every section is laid out in. */
@Composable
private fun SectionColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().widthIn(max = 900.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) { content() }
}

@Composable
private fun AccountSection(
    vm: SettingsViewModel,
    focus: FocusRequester,
    onAddAccount: () -> Unit,
    onSwitchAccount: (Long) -> Unit,
) {
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val never = stringResource(R.string.settings_never)
    fun fmt(t: Long?) = t?.let { dateFormat.format(Date(it)) } ?: never

    SectionColumn {
        // Profiles: OK on another account switches to it; the details below belong to the active one.
        SettingsGroup(stringResource(R.string.settings_accounts)) {
            accounts.forEachIndexed { i, a ->
                if (i > 0) GroupDivider()
                val active = a.id == playlist?.id
                ValueRow(
                    title = "${a.name} · ${a.username}",
                    value = stringResource(if (active) R.string.settings_account_active else R.string.settings_account_switch),
                    valueActive = active,
                    chevron = !active,
                    onClick = { if (!active) onSwitchAccount(a.id) },
                    modifier = if (active) Modifier.focusRequester(focus) else Modifier,
                )
            }
            GroupDivider()
            ValueRow(title = stringResource(R.string.settings_account_add), value = "", onClick = onAddAccount)
        }
        playlist?.let { p ->
            SettingsGroup(stringResource(R.string.settings_group_active_account)) {
                // Saved when the keyboard closes or the field is left; blank goes back to the server address.
                var name by remember(p.id, p.name) { mutableStateOf(p.name) }
                TvTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.settings_account_name),
                    imeAction = ImeAction.Done,
                    onImeAction = { if (name != p.name) vm.renameAccount(name) },
                    commitOnLeave = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                )
                InfoLine(stringResource(R.string.settings_status, p.status))
                InfoLine(p.expiresAt?.let { stringResource(R.string.settings_expires, fmt(it)) } ?: stringResource(R.string.settings_expires_never))
                InfoLine(stringResource(R.string.settings_connections, p.maxConnections))
                InfoLine(stringResource(R.string.settings_last_refresh, fmt(p.channelsUpdatedAt)))
                InfoLine(stringResource(R.string.settings_epg_updated, fmt(p.epgUpdatedAt)))
                p.epgError?.let { InfoLine(epgErrorText(it), Brand.Error) }
                Spacer(Modifier.height(6.dp))
            }
            SettingsGroup(stringResource(R.string.settings_group_server)) {
                // Checked against the server when the keyboard closes; a bad address puts the old one back.
                var server by remember(p.id, p.server) { mutableStateOf(p.server) }
                val scope = rememberCoroutineScope()
                TvTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = stringResource(R.string.settings_server),
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    onImeAction = { scope.launch { if (!vm.changeServer(server)) server = p.server } },
                    commitOnLeave = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                )
                InfoLine(stringResource(R.string.settings_server_hint), Brand.TextSecondary, small = true)
                // Field state survives typing; it is saved when the keyboard closes.
                var epgUrl by remember(p.id) { mutableStateOf(p.epgUrl.orEmpty()) }
                TvTextField(
                    value = epgUrl,
                    onValueChange = { epgUrl = it },
                    label = stringResource(R.string.settings_epg_url),
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    onImeAction = { vm.setEpgUrl(epgUrl) },
                    commitOnLeave = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                )
                InfoLine(stringResource(R.string.settings_epg_url_hint), Brand.TextSecondary, small = true)
                GroupDivider()
                val presets by vm.epgPresets.collectAsStateWithLifecycle()
                var choosePresets by remember { mutableStateOf(false) }
                val locale = LocalConfiguration.current.locales[0]
                val labels = remember(locale) { EpgPresets.labels(locale) }
                ValueRow(
                    title = stringResource(R.string.settings_epg_presets),
                    hint = stringResource(R.string.settings_epg_presets_hint),
                    value = EpgPresets.all.filter { it.id in presets }.map { labels.getValue(it.id) }.let { names ->
                        // A long choice would push the title out of the row.
                        names.take(3).joinToString(", ") + if (names.size > 3) " +${names.size - 3}" else ""
                    }.ifEmpty { stringResource(R.string.settings_epg_presets_none) },
                    valueActive = presets.isNotEmpty(),
                    onClick = { choosePresets = true },
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
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                status?.let { Text(it, color = Brand.TextSecondary) }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvButton(stringResource(R.string.settings_refresh), onClick = vm::refresh, style = ButtonStyle.Neutral)
                    TvButton(stringResource(R.string.settings_logout), onClick = { confirmRemove = true }, style = ButtonStyle.Danger)
                }
            }
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

/** A line of text inside a group card. */
@Composable
private fun InfoLine(text: String, color: Color = Brand.TextPrimary, small: Boolean = false) {
    Text(
        text,
        color = color,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = if (small) 2.dp else 5.dp),
    )
}

@Composable
private fun epgErrorText(error: String): String = when {
    error == EpgRepository.NO_MATCH -> stringResource(R.string.settings_epg_no_match)
    error.startsWith("EPG HTTP ") -> stringResource(R.string.settings_epg_http, error.removePrefix("EPG HTTP "))
    else -> stringResource(R.string.settings_epg_failed)
}

private val categoryTabs = listOf(
    CategoryTab.LIVE to R.string.nav_tv,
    CategoryTab.MOVIES to R.string.nav_movies,
    CategoryTab.SERIES to R.string.nav_series,
)

@Composable
private fun CategoriesSection(vm: SettingsViewModel, focus: FocusRequester) {
    val tab by vm.categoryTab.collectAsStateWithLifecycle()
    val state by vm.categories.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            categoryTabs.forEach { (t, label) ->
                Chip(
                    text = stringResource(label),
                    selected = t == tab,
                    onClick = { vm.selectCategoryTab(t) },
                    modifier = if (t == tab) Modifier.focusRequester(focus) else Modifier,
                )
            }
            Spacer(Modifier.weight(1f))
            TvButton(stringResource(R.string.settings_show_all), onClick = { vm.setAllCategoriesHidden(false) }, style = ButtonStyle.Neutral)
            TvButton(stringResource(R.string.settings_hide_all), onClick = { vm.setAllCategoriesHidden(true) }, style = ButtonStyle.Neutral)
        }
        Text(
            stringResource(if (tab == CategoryTab.LIVE) R.string.settings_categories_hint else R.string.settings_categories_hint_vod),
            color = Brand.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        CategoryGrid(state) { item ->
            SettingRow(
                title = item.name,
                value = stringResource(if (item.hidden) R.string.category_hidden else R.string.category_shown),
                valueActive = !item.hidden,
                onClick = { vm.toggleCategory(item) },
            )
        }
    }
}

@Composable
private fun CategoryGrid(state: CategoriesState, item: @Composable (CategoryToggle) -> Unit) {
    when (state) {
        CategoriesState.Loading -> Text(stringResource(R.string.vod_loading), color = Brand.TextSecondary)
        is CategoriesState.Error -> Text(state.message, color = Brand.Error)
        is CategoriesState.Ready -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(state.items, key = { it.key }) { item(it) }
        }
    }
}

/**
 * Shows [content] only while the parental locks are not in force; otherwise a note and a button that asks for
 * the PIN (not asked on its own: the section shows as soon as the menu passes over it).
 */
@Composable
private fun PinGate(vm: SettingsViewModel, focus: FocusRequester, hold: NavHold, content: @Composable () -> Unit) {
    val active by vm.parentalActive.collectAsStateWithLifecycle()
    // After the PIN the row is gone: focus moves into the section's content.
    LaunchedEffect(active) {
        if (!active && hold.active) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
            hold.active = false
        }
    }
    if (!active) {
        content()
        return
    }
    SettingsGroup(null) {
        InfoLine(stringResource(R.string.parental_locked_section), Brand.TextSecondary)
        ValueRow(
            title = stringResource(R.string.pin_enter_title),
            value = "",
            onClick = {
                hold.active = true
                vm.requestUnlock(onCancel = { hold.active = false })
            },
            modifier = Modifier.focusRequester(focus),
        )
    }
}

@Composable
private fun ParentalSection(vm: SettingsViewModel, focus: FocusRequester, hold: NavHold) {
    val enabled by vm.parentalEnabled.collectAsStateWithLifecycle()
    val message by vm.parentalMessage.collectAsStateWithLifecycle()
    var choosingPin by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    // The rows change when the PIN is set or removed; focus goes to the first one again.
    var refocus by remember { mutableIntStateOf(0) }
    LaunchedEffect(refocus) {
        if (refocus == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { focus.requestFocus() }
        hold.active = false
    }
    if (choosingPin) {
        PinSetupDialog(
            onDone = { pin ->
                choosingPin = false
                hold.active = true
                vm.setPin(pin)
                refocus++
            },
            onDismiss = { choosingPin = false },
        )
    }
    if (confirmOff) {
        ConfirmDialog(
            title = stringResource(R.string.parental_off_title),
            message = stringResource(R.string.parental_off_message),
            confirmLabel = stringResource(R.string.parental_turn_off),
            onConfirm = {
                confirmOff = false
                hold.active = true
                vm.turnOffParental()
                refocus++
            },
            onDismiss = { confirmOff = false },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(stringResource(R.string.parental_intro), color = Brand.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        SettingsGroup(stringResource(R.string.settings_group_pin)) {
            if (!enabled) {
                ValueRow(
                    title = stringResource(R.string.parental_set_pin),
                    value = stringResource(R.string.state_off),
                    onClick = { choosingPin = true },
                    modifier = Modifier.focusRequester(focus),
                )
            } else {
                ValueRow(
                    title = stringResource(R.string.parental_lock_adult),
                    value = message.orEmpty(),
                    valueActive = true,
                    onClick = vm::lockAdultCategories,
                    modifier = Modifier.focusRequester(focus),
                )
                GroupDivider()
                ValueRow(title = stringResource(R.string.parental_change_pin), value = "", onClick = { choosingPin = true })
                GroupDivider()
                ValueRow(title = stringResource(R.string.parental_turn_off), value = "", danger = true, chevron = false, onClick = { confirmOff = true })
            }
        }
        if (enabled) LockCategoryList(vm)
    }
}

@Composable
private fun LockCategoryList(vm: SettingsViewModel) {
    val tab by vm.lockTab.collectAsStateWithLifecycle()
    val state by vm.lockCategories.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.settings_group_locked), fontSize = 13.sp, color = Brand.TextSecondary, modifier = Modifier.padding(start = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            categoryTabs.forEach { (t, label) -> Chip(text = stringResource(label), selected = t == tab, onClick = { vm.selectLockTab(t) }) }
        }
        CategoryGrid(state) { item ->
            SettingRow(
                title = item.name,
                value = stringResource(if (item.hidden) R.string.category_locked else R.string.category_unlocked),
                valueActive = item.hidden,
                onClick = { vm.toggleLock(item) },
            )
        }
    }
}

@Composable
private fun PlaybackSection(vm: SettingsViewModel, focus: FocusRequester) {
    val backgroundPlayback by vm.backgroundPlayback.collectAsStateWithLifecycle()
    val limitVideo by vm.limitVideo.collectAsStateWithLifecycle()
    val secureDns by vm.secureDns.collectAsStateWithLifecycle()
    val textureVideo by vm.textureVideo.collectAsStateWithLifecycle()
    val standbyAwake by vm.standbyAwake.collectAsStateWithLifecycle()
    val decoderMode by vm.decoderMode.collectAsStateWithLifecycle()
    val bufferMode by vm.bufferMode.collectAsStateWithLifecycle()
    var decoderDialog by remember { mutableStateOf(false) }
    var bufferDialog by remember { mutableStateOf(false) }
    val decoderLabels = mapOf(
        rs.timok.player.player.DecoderMode.HARDWARE to stringResource(R.string.settings_decoder_hardware),
        rs.timok.player.player.DecoderMode.SOFTWARE to stringResource(R.string.settings_decoder_software),
    )
    val bufferLabels = mapOf(
        rs.timok.player.player.BufferMode.AUTO to stringResource(R.string.settings_buffer_auto),
        rs.timok.player.player.BufferMode.SHORT to stringResource(R.string.settings_buffer_short),
        rs.timok.player.player.BufferMode.LONG to stringResource(R.string.settings_buffer_long),
    )
    if (decoderDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_decoder),
            options = decoderLabels.toList(),
            current = decoderMode,
            onPick = { decoderDialog = false; vm.setDecoderMode(it) },
            onDismiss = { decoderDialog = false },
        )
    }
    if (bufferDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_buffer),
            options = bufferLabels.toList(),
            current = bufferMode,
            onPick = { bufferDialog = false; vm.setBufferMode(it) },
            onDismiss = { bufferDialog = false },
        )
    }
    SectionColumn {
        SettingsGroup(stringResource(R.string.settings_group_video)) {
            SwitchRow(
                title = stringResource(R.string.settings_background_video),
                hint = stringResource(R.string.settings_background_video_hint),
                checked = backgroundPlayback,
                onClick = vm::toggleBackgroundPlayback,
                modifier = Modifier.focusRequester(focus),
            )
            GroupDivider()
            SwitchRow(
                title = stringResource(R.string.settings_limit_video),
                hint = stringResource(R.string.settings_limit_video_hint),
                checked = limitVideo,
                onClick = vm::toggleLimitVideo,
            )
            GroupDivider()
            ValueRow(
                title = stringResource(R.string.settings_decoder),
                hint = stringResource(R.string.settings_decoder_hint),
                value = decoderLabels.getValue(decoderMode),
                valueActive = decoderMode != rs.timok.player.player.DecoderMode.HARDWARE,
                onClick = { decoderDialog = true },
            )
            GroupDivider()
            SwitchRow(
                title = stringResource(R.string.settings_texture_video),
                hint = stringResource(R.string.settings_texture_video_hint),
                checked = textureVideo,
                onClick = vm::toggleTextureVideo,
            )
        }
        SettingsGroup(stringResource(R.string.settings_group_network)) {
            ValueRow(
                title = stringResource(R.string.settings_buffer),
                hint = stringResource(R.string.settings_buffer_hint),
                value = bufferLabels.getValue(bufferMode),
                valueActive = bufferMode != rs.timok.player.player.BufferMode.AUTO,
                onClick = { bufferDialog = true },
            )
            GroupDivider()
            SwitchRow(
                title = stringResource(R.string.settings_secure_dns),
                hint = stringResource(R.string.settings_secure_dns_hint),
                checked = secureDns,
                onClick = vm::toggleSecureDns,
            )
        }
        SettingsGroup(stringResource(R.string.settings_group_device)) {
            SwitchRow(
                title = stringResource(R.string.settings_standby_awake),
                hint = stringResource(R.string.settings_standby_awake_hint),
                checked = standbyAwake,
                onClick = vm::toggleStandbyAwake,
            )
        }
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
    val screenMargin by vm.screenMargin.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    var languageDialog by remember { mutableStateOf(false) }
    SectionColumn {
        SettingsGroup(stringResource(R.string.settings_group_display)) {
            ValueRow(
                title = stringResource(R.string.settings_language),
                value = languageLabel(language),
                valueActive = true,
                onClick = { languageDialog = true },
                modifier = Modifier.focusRequester(focus),
            )
            GroupDivider()
            ChipsRow(
                title = stringResource(R.string.settings_display_size),
                hint = stringResource(R.string.settings_display_size_hint),
                options = AppPrefs.uiScales,
                selected = uiScale,
                label = { percent(it) },
                onPick = vm::setUiScale,
            )
            GroupDivider()
            ChipsRow(
                title = stringResource(R.string.settings_screen_margin),
                hint = stringResource(R.string.settings_screen_margin_hint),
                options = AppPrefs.screenMargins,
                selected = screenMargin,
                label = { "$it%" },
                onPick = vm::setScreenMargin,
            )
        }
        SettingsGroup(stringResource(R.string.settings_group_look)) {
            ChipsRow(
                title = stringResource(R.string.settings_accent),
                options = AppPrefs.accents,
                selected = accent,
                label = { name ->
                    "● " + stringResource(
                        when (name) {
                            "blue" -> R.string.accent_blue
                            "green" -> R.string.accent_green
                            "purple" -> R.string.accent_purple
                            else -> R.string.accent_orange
                        }
                    )
                },
                onPick = vm::setAccent,
            )
            GroupDivider()
            SwitchRow(
                title = stringResource(R.string.settings_animations),
                hint = stringResource(R.string.settings_animations_hint),
                checked = animations,
                onClick = vm::toggleAnimations,
            )
        }
        SettingsGroup(stringResource(R.string.settings_group_list)) {
            SwitchRow(
                title = stringResource(R.string.settings_compact_list),
                hint = stringResource(R.string.settings_compact_list_hint),
                checked = compactList,
                onClick = vm::toggleCompactList,
            )
            GroupDivider()
            ChipsRow(
                title = stringResource(R.string.settings_list_fullscreen),
                hint = stringResource(R.string.settings_list_fullscreen_hint),
                options = AppPrefs.listFullscreenDelays,
                selected = listFullscreen,
                label = { seconds ->
                    when {
                        seconds == 0 -> stringResource(R.string.settings_never_option)
                        seconds >= 60 -> stringResource(R.string.settings_minutes, seconds / 60)
                        else -> stringResource(R.string.settings_seconds, seconds)
                    }
                },
                onPick = vm::setListFullscreen,
            )
        }
        SettingsGroup(stringResource(R.string.settings_group_watching)) {
            SwitchRow(
                title = stringResource(R.string.settings_stream_badges),
                hint = stringResource(R.string.settings_stream_badges_hint),
                checked = streamBadges,
                onClick = vm::toggleStreamBadges,
            )
            GroupDivider()
            SwitchRow(
                title = stringResource(R.string.settings_clock_overlay),
                hint = stringResource(R.string.settings_clock_overlay_hint),
                checked = clockOverlay,
                onClick = vm::toggleClockOverlay,
            )
            GroupDivider()
            ChipsRow(
                title = stringResource(R.string.settings_panel_timeout),
                hint = stringResource(R.string.settings_panel_timeout_hint),
                options = AppPrefs.panelTimeouts,
                selected = panelTimeout,
                label = { stringResource(R.string.settings_seconds, it) },
                onPick = vm::setPanelTimeout,
            )
        }
    }
    if (languageDialog) {
        LanguageDialog(
            current = language,
            onPick = { tag ->
                languageDialog = false
                if (tag != language) {
                    vm.setLanguage(tag)
                    // Activity strings are resolved at attach time, so rebuild it in the new language.
                    activity?.recreate()
                }
            },
            onDismiss = { languageDialog = false },
        )
    }
}

@Composable
private fun AutoStartSection(vm: SettingsViewModel, focus: FocusRequester) {
    val autoStart by vm.autoStart.collectAsStateWithLifecycle()
    val blocked by vm.autoStartBlocked.collectAsStateWithLifecycle()
    val noGrantScreen by vm.noGrantScreen.collectAsStateWithLifecycle()
    SectionColumn {
        SettingsGroup(null) {
            SwitchRow(
                title = stringResource(R.string.settings_autostart),
                hint = stringResource(R.string.settings_autostart_hint),
                checked = autoStart,
                onClick = vm::toggleAutoStart,
                modifier = Modifier.focusRequester(focus),
            )
            if (blocked) {
                GroupDivider()
                ValueRow(
                    title = stringResource(R.string.settings_autostart_grant),
                    hint = stringResource(R.string.settings_autostart_permission),
                    value = "⚠",
                    valueActive = true,
                    onClick = vm::requestAutoStartGrant,
                )
            }
        }
        if (blocked) {
            Text(stringResource(R.string.settings_autostart_permission), color = Brand.Accent, style = MaterialTheme.typography.bodyMedium)
            if (noGrantScreen) {
                Text(stringResource(R.string.settings_autostart_no_screen), color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun percent(scale: Float) = "${(scale * 100).roundToInt()}%"

/** Drop-down list of UI languages; opens on the current one, OK picks, Back closes. */
@Composable
private fun LanguageDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val currentFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(480.dp)
                .background(Brand.Background, tileShape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.settings_language),
                style = MaterialTheme.typography.titleLarge,
                color = Brand.TextPrimary,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
            AppPrefs.languages.forEach { tag ->
                val selected = tag == current
                TvClickable(
                    onClick = { onPick(tag) },
                    selected = selected,
                    modifier = Modifier.fillMaxWidth().then(if (selected) Modifier.focusRequester(currentFocus) else Modifier),
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (selected) "●" else "○",
                            color = if (selected) Brand.Accent else Brand.TextSecondary,
                            modifier = Modifier.width(32.dp),
                        )
                        Text(
                            languageLabel(tag),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) Brand.Accent else Brand.TextPrimary,
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { currentFocus.requestFocus() } }
}

/** One-of-many choice in the style of the language list: focus on the current value, Back closes. */
@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    val currentFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(560.dp)
                .background(Brand.Background, tileShape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = Brand.TextPrimary,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
            options.forEach { (value, label) ->
                val selected = value == current
                TvClickable(
                    onClick = { onPick(value) },
                    selected = selected,
                    modifier = Modifier.fillMaxWidth().then(if (selected) Modifier.focusRequester(currentFocus) else Modifier),
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (selected) "●" else "○",
                            color = if (selected) Brand.Accent else Brand.TextSecondary,
                            modifier = Modifier.width(32.dp),
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) Brand.Accent else Brand.TextPrimary,
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { currentFocus.requestFocus() } }
}

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

/** New PIN typed twice; a mismatch starts over. */
@Composable
private fun PinSetupDialog(onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    val mismatch = stringResource(R.string.pin_mismatch)
    rs.timok.player.ui.components.PinDialog(
        title = stringResource(if (first == null) R.string.pin_new_title else R.string.pin_repeat_title),
        message = stringResource(R.string.pin_set_message),
        onPin = { pin ->
            when {
                first == null -> {
                    first = pin
                    null
                }
                pin == first -> {
                    onDone(pin)
                    null
                }
                else -> {
                    first = null
                    mismatch
                }
            }
        },
        onDismiss = onDismiss,
    )
}

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

