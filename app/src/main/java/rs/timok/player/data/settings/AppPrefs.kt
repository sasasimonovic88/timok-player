package rs.timok.player.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rs.timok.player.epg.EpgPresets

/**
 * Settings that must be readable synchronously (or before the first frame) before Hilt and DataStore are available:
 * the UI language (needed in attachBaseContext), autostart (read by the boot receiver), secure DNS (read on
 * OkHttp threads for every lookup) and the built-in guides (read by the guide worker).
 */
object AppPrefs {
    private const val FILE = "app_prefs"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_UI_SCALE = "ui_scale"
    private const val KEY_SECURE_DNS = "secure_dns"
    private const val KEY_EPG_PRESETS = "epg_presets"
    private const val KEY_ANIMATIONS = "animations"
    private const val KEY_PANEL_TIMEOUT = "panel_timeout_s"
    private const val KEY_LIST_FULLSCREEN = "list_fullscreen_s"
    private const val KEY_COMPACT_LIST = "compact_list"
    private const val KEY_STREAM_BADGES = "stream_badges"
    private const val KEY_CLOCK_OVERLAY = "clock_overlay"
    private const val KEY_ACCENT = "accent"

    /** Accent color choices (focus outline and highlights); the first is the brand orange. */
    val accents = listOf("orange", "blue", "green", "purple")

    private val _compactList = MutableStateFlow(false)
    /** Lower channel rows without the "next" line, so more channels fit on screen. */
    val compactList: StateFlow<Boolean> = _compactList

    private val _streamBadges = MutableStateFlow(true)
    /** Resolution, fps, codec and bitrate badges on the info bar. */
    val streamBadges: StateFlow<Boolean> = _streamBadges

    private val _clockOverlay = MutableStateFlow(false)
    /** Small clock in the corner while watching. */
    val clockOverlay: StateFlow<Boolean> = _clockOverlay

    private val _accent = MutableStateFlow("orange")
    val accent: StateFlow<String> = _accent

    /** Seconds the info bar stays up; the other panels scale with it (see [panelTimeoutMs]). */
    val panelTimeouts = listOf(3, 5, 10)

    /** Seconds of inactivity before the channel list turns into fullscreen video; 0 = never. */
    val listFullscreenDelays = listOf(10, 20, 60, 0)

    private val _animations = MutableStateFlow(true)

    /** Scrolling, screen transitions, panels and focus scale animate; off makes everything jump (weak boxes). */
    val animations: StateFlow<Boolean> = _animations

    private val _panelTimeout = MutableStateFlow(5)
    val panelTimeout: StateFlow<Int> = _panelTimeout

    private val _listFullscreen = MutableStateFlow(20)
    val listFullscreen: StateFlow<Int> = _listFullscreen

    /**
     * How long a panel stays up without input. [baseMs] is its time at the default 5 s setting (info bar 5 s,
     * channel overlay 8 s, options 15 s...), so all panels get shorter or longer together.
     */
    fun panelTimeoutMs(baseMs: Long): Long = baseMs * _panelTimeout.value / 5

    /** Supported UI languages; an empty tag means "follow the system". */
    val languages = listOf("", "sr", "en", "de", "sk")

    /** Display size steps; 1.0 is the original layout. */
    val uiScales = listOf(0.7f, 0.8f, 0.9f, 1.0f)

    private val _uiScale = MutableStateFlow(1f)

    /** Current display size, read by the theme; loaded in [init] before the first frame. */
    val uiScale: StateFlow<Float> = _uiScale

    private val _secureDns = MutableStateFlow(true)

    /** Resolve host names over HTTPS instead of the network's DNS; on by default. */
    val secureDns: StateFlow<Boolean> = _secureDns

    private val _epgPresets = MutableStateFlow(EpgPresets.defaultIds)

    /**
     * Ids of the built-in guides ([EpgPresets]) to fall back on when the provider serves none. Kept app-wide, not
     * per account, so removing and adding an account again does not lose them.
     */
    val epgPresets: StateFlow<Set<String>> = _epgPresets

    fun init(context: Context) {
        _uiScale.value = prefs(context).getFloat(KEY_UI_SCALE, 1f)
        _secureDns.value = prefs(context).getBoolean(KEY_SECURE_DNS, true)
        _epgPresets.value = prefs(context).getStringSet(KEY_EPG_PRESETS, null)?.toSet() ?: EpgPresets.defaultIds
        _animations.value = prefs(context).getBoolean(KEY_ANIMATIONS, true)
        _panelTimeout.value = prefs(context).getInt(KEY_PANEL_TIMEOUT, 5)
        _listFullscreen.value = prefs(context).getInt(KEY_LIST_FULLSCREEN, 20)
        _compactList.value = prefs(context).getBoolean(KEY_COMPACT_LIST, false)
        _streamBadges.value = prefs(context).getBoolean(KEY_STREAM_BADGES, true)
        _clockOverlay.value = prefs(context).getBoolean(KEY_CLOCK_OVERLAY, false)
        _accent.value = prefs(context).getString(KEY_ACCENT, null)?.takeIf { it in accents } ?: "orange"
    }

    fun setCompactList(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_COMPACT_LIST, enabled).apply()
        _compactList.value = enabled
    }

    fun setStreamBadges(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STREAM_BADGES, enabled).apply()
        _streamBadges.value = enabled
    }

    fun setClockOverlay(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLOCK_OVERLAY, enabled).apply()
        _clockOverlay.value = enabled
    }

    fun setAccent(context: Context, name: String) {
        if (name !in accents) return
        prefs(context).edit().putString(KEY_ACCENT, name).apply()
        _accent.value = name
    }

    fun setAnimations(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANIMATIONS, enabled).apply()
        _animations.value = enabled
    }

    fun setPanelTimeout(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_PANEL_TIMEOUT, seconds).apply()
        _panelTimeout.value = seconds
    }

    fun setListFullscreen(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_LIST_FULLSCREEN, seconds).apply()
        _listFullscreen.value = seconds
    }

    fun setEpgPresets(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY_EPG_PRESETS, ids).apply()
        _epgPresets.value = ids
    }

    fun setSecureDns(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SECURE_DNS, enabled).apply()
        _secureDns.value = enabled
    }

    fun setUiScale(context: Context, scale: Float) {
        prefs(context).edit().putFloat(KEY_UI_SCALE, scale).apply()
        _uiScale.value = scale
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun language(context: Context): String = prefs(context).getString(KEY_LANGUAGE, "").orEmpty()

    fun setLanguage(context: Context, tag: String) = prefs(context).edit().putString(KEY_LANGUAGE, tag).apply()

    fun autoStart(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTOSTART, false)

    fun setAutoStart(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_AUTOSTART, enabled).apply()
}
