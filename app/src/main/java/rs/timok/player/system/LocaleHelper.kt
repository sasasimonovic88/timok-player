package rs.timok.player.system

import android.content.Context
import android.content.res.Configuration
import rs.timok.player.data.settings.AppPrefs
import java.util.Locale

/** Applies the in-app language chosen in settings on top of the system configuration. */
object LocaleHelper {

    fun wrap(base: Context): Context {
        val tag = AppPrefs.language(base)
        if (tag.isEmpty()) return base
        val (resources, formatting) = locales(tag)
        // Dates and numbers use the default locale; strings use the configuration.
        Locale.setDefault(formatting)
        val config = Configuration(base.resources.configuration)
        config.setLocale(resources)
        return base.createConfigurationContext(config)
    }

    /**
     * Updates an already attached context (the application) after the language changed,
     * so strings resolved outside activities follow too.
     */
    @Suppress("DEPRECATION")
    fun apply(context: Context) {
        val tag = AppPrefs.language(context)
        val (resources, formatting) = if (tag.isEmpty()) {
            val system = android.content.res.Resources.getSystem().configuration
            val l = if (android.os.Build.VERSION.SDK_INT >= 24) system.locales[0] else system.locale
            l to l
        } else locales(tag)
        Locale.setDefault(formatting)
        val config = Configuration(context.resources.configuration)
        config.setLocale(resources)
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    /** Native name of a language for the picker. */
    fun displayName(tag: String): String = when (tag) {
        "sr" -> "Srpski"
        "en" -> "English"
        "de" -> "Deutsch"
        "sk" -> "Slovenčina"
        else -> ""
    }

    /**
     * Serbian strings are in Latin script under values-sr; a plain "sr" locale matches them,
     * while dates would default to Cyrillic, so formatting uses sr-Latn explicitly.
     */
    private fun locales(tag: String): Pair<Locale, Locale> = when (tag) {
        "sr" -> Locale("sr", "RS") to Locale.Builder().setLanguage("sr").setScript("Latn").setRegion("RS").build()
        else -> Locale(tag) to Locale(tag)
    }
}
