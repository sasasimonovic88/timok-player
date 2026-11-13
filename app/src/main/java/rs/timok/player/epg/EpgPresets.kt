package rs.timok.player.epg

import java.util.Locale

/**
 * A public XMLTV guide built into the app (epgshare01 per-country and per-service files), used when the provider
 * serves no guide of its own. Either [country] (ISO code, named in the UI language) or [name] labels it.
 */
data class EpgPreset(val file: String, val country: String? = null, val name: String? = null) {
    val id: String get() = file.lowercase()
    val url: String get() = "$BASE$file.xml.gz"

    fun label(locale: Locale): String = name ?: Locale("", country!!).getDisplayCountry(locale).let {
        // Serbian country names come in Cyrillic; the app is written in Latin script.
        if (locale.language == "sr") toLatin(it) else it
    }

    private companion object {
        const val BASE = "https://epgshare01.online/epgshare01/epg_ripper_"
        val CYRILLIC = "абвгдђежзијклљмнњопрстћуфхцчџшАБВГДЂЕЖЗИЈКЛЉМНЊОПРСТЋУФХЦЧЏШ"
        val LATIN = listOf(
            "a", "b", "v", "g", "d", "đ", "e", "ž", "z", "i", "j", "k", "l", "lj", "m", "n", "nj", "o", "p", "r", "s",
            "t", "ć", "u", "f", "h", "c", "č", "dž", "š",
            "A", "B", "V", "G", "D", "Đ", "E", "Ž", "Z", "I", "J", "K", "L", "Lj", "M", "N", "Nj", "O", "P", "R", "S",
            "T", "Ć", "U", "F", "H", "C", "Č", "Dž", "Š",
        )

        fun toLatin(text: String) = buildString {
            for (c in text) {
                val i = CYRILLIC.indexOf(c)
                append(if (i >= 0) LATIN[i] else c)
            }
        }
    }
}

object EpgPresets {
    /** Services first, then countries. Huge files (all sources, US locals) are left out: a TV box cannot parse them. */
    val all = listOf(
        EpgPreset("SPORTKLUB1", name = "Sport Klub"),
        EpgPreset("BEIN1", name = "beIN Sports"),
        EpgPreset("US_SPORTS1", name = "US Sports"),
        EpgPreset("ALJAZEERA1", name = "Al Jazeera"),
        EpgPreset("PLEX1", name = "Plex"),
        EpgPreset("RAKUTEN1", name = "Rakuten TV"),
        EpgPreset("PEACOCK1", name = "Peacock"),
        EpgPreset("DISTROTV1", name = "Distro TV"),
    ) + listOf(
        "RS1" to "RS", "BA1" to "BA", "HR1" to "HR", "AL1" to "AL", "BG1" to "BG", "RO1" to "RO", "HU1" to "HU",
        "GR1" to "GR", "TR1" to "TR", "TR3" to "TR", "AT1" to "AT", "DE1" to "DE", "CH1" to "CH", "IT1" to "IT",
        "FR1" to "FR", "ES1" to "ES", "PT1" to "PT", "UK1" to "GB", "IE1" to "IE", "NL1" to "NL", "BE2" to "BE",
        "LU1" to "LU", "DK1" to "DK", "SE1" to "SE", "NO1" to "NO", "FI1" to "FI", "PL1" to "PL", "CZ1" to "CZ",
        "SK1" to "SK", "LT1" to "LT", "LV1" to "LV", "UA1" to "UA", "CY1" to "CY", "MT1" to "MT", "IL1" to "IL",
        "AE1" to "AE", "US2" to "US", "CA2" to "CA", "MX1" to "MX", "BR1" to "BR", "BR2" to "BR", "AR1" to "AR",
        "CL1" to "CL", "CO1" to "CO", "PE1" to "PE", "EC1" to "EC", "UY1" to "UY", "CR1" to "CR", "PA1" to "PA",
        "DO1" to "DO", "SV1" to "SV", "JM1" to "JM", "BB1" to "BB", "AU1" to "AU", "NZ1" to "NZ", "IN1" to "IN",
        "IN4" to "IN", "JP1" to "JP", "JP2" to "JP", "KR1" to "KR", "HK1" to "HK", "SG1" to "SG", "MY1" to "MY",
        "ID1" to "ID", "TH1" to "TH", "VN1" to "VN", "PH2" to "PH", "ZA1" to "ZA", "NG1" to "NG", "KE1" to "KE",
    ).map { (file, country) -> EpgPreset(file, country = country) }

    /** Checked on a fresh install. */
    val defaultIds: Set<String> = setOf("rs1", "sportklub1")

    /** Countries with more than one file get "2", "3"... after the second one, e.g. "Japan 2". */
    fun labels(locale: Locale): Map<String, String> {
        val seen = HashMap<String, Int>()
        return all.associate { preset ->
            val base = preset.label(locale)
            val n = seen.merge(base, 1, Int::plus)!!
            preset.id to if (n > 1) "$base $n" else base
        }
    }
}
