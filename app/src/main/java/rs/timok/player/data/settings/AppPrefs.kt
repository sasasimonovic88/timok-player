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
