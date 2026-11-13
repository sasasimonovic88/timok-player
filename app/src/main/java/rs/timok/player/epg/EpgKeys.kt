package rs.timok.player.epg

import java.text.Normalizer

/**
 * Guide keys of channels. Channels with a server EPG id use it; the others are matched to XMLTV
 * `<channel>` entries by name, which is what makes external guides (other ids than the panel) usable.
 */
object EpgKeys {
    private const val NAME_PREFIX = "n:"
    private val MARKS = Regex("\\p{Mn}+")
    /** "RS: ", "HR | ", "[EX-YU] " style country/group prefixes. */
    private val GROUP_PREFIX = Regex("^\\s*(\\[[^\\]]{1,12}\\]|[a-z]{2,4}\\s*[:|])\\s*")
    /** Anything in brackets: "(RS)", "(SR/BIH)", "(Portals)", "[HR]". */
    private val BRACKETS = Regex("\\([^)]*\\)|\\[[^\\]]*\\]")
    /** Country and region words that providers and guides add or leave out. */
    private val REGION = Regex("\\b(rs|srb|serbia|hr|cro|ba|bih|si|slo|me|mk|exyu|yu)\\b")
    /** Quality, codec and package tags that guides usually leave out. */
    private val TRAILING_ONE = Regex("\\s+1$")
    private val QUALITY = Regex("\\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|1080p|720p|backup|raw|vip)\\b")

    /** Leading code of a panel channel name: "HR| ", "RS: ", "[EX-YU] ". */
    private val CODE_PREFIX = Regex("^\\s*(?:\\[([^\\]]{1,12})\\]|([A-Za-z-]{2,5})\\s*[:|])")
    /** Panel codes that are not ISO country codes. */
    private val CODE_ALIASES = mapOf(
        "SRB" to "RS", "CRO" to "HR", "BH" to "BA", "BIH" to "BA", "CG" to "ME", "MNE" to "ME", "SL" to "SI",
        "SLO" to "SI", "UK" to "GB", "GER" to "DE", "EXYU" to EXYU, "EX-YU" to EXYU, "YU" to EXYU, "EN" to ENGLISH,
    )
    /** Countries that share a language closely enough for one another's guides. */
    private val GROUPS = mapOf(
        EXYU to setOf("RS", "HR", "BA", "ME", "MK", "SI"),
        ENGLISH to setOf("GB", "US", "IE", "CA", "AU", "NZ"),
        "DACH" to setOf("DE", "AT", "CH"),
    )

    /**
     * Name-matched channels carry their country in the key, so "RS| HGTV" and "HR| HGTV" stay apart and each
     * can only take a guide from its own region (see [compatible]).
     */
    fun key(epgChannelId: String?, name: String): String =
        epgChannelId?.takeIf { it.isNotBlank() }
            ?: (NAME_PREFIX + (countryOf(name)?.let { "$it:" } ?: "") + normalizeName(name))

    /**
     * Country (ISO code, or the [EXYU] / [ENGLISH] group) from a panel name prefix such as "HR| " or "[EX-YU] ";
     * null when there is none or it is not a country ("MULTI|", "VIP|").
     */
    fun countryOf(name: String): String? {
        val m = CODE_PREFIX.find(name) ?: return null
        val code = (m.groupValues[1].ifEmpty { m.groupValues[2] }).uppercase().replace(" ", "")
        CODE_ALIASES[code]?.let { return it }
        return code.takeIf { it.length == 2 && it.all(Char::isLetter) && it in ISO_COUNTRIES }
    }

    /** Country of a guide channel from its XMLTV id suffix, e.g. "HGTV.ro" -> "RO", "BBC.One.uk" -> "GB". */
    fun countryOfGuideId(id: String): String? {
        val suffix = id.substringAfterLast('.', "").uppercase()
        if (suffix.length != 2) return null
        return CODE_ALIASES[suffix] ?: suffix.takeIf { it in ISO_COUNTRIES }
    }

    /** Whether a guide channel from [guide] may stand for a channel from [channel]; unknown on either side matches. */
    fun compatible(channel: String?, guide: String?): Boolean {
        if (channel == null || guide == null || channel == guide) return true
        GROUPS[channel]?.let { return guide in it }
        return GROUPS.values.any { channel in it && guide in it }
    }

    /** Country part of a name key made by [key], if any. */
    fun countryOfKey(key: String): String? {
        if (!key.startsWith(NAME_PREFIX)) return null
        val rest = key.substring(NAME_PREFIX.length)
        return rest.substringBefore(':', "").takeIf { it.isNotEmpty() }
    }

    private const val EXYU = "EXYU"
    private const val ENGLISH = "EN"
    private val ISO_COUNTRIES = java.util.Locale.getISOCountries().toHashSet()

    /** "RS: RTS 1 HD", "RTS 1 HD (RS)" and "RTS1" all become "rts1"; "Sport Klub 2 SRB" and "SK 2 HR (Portals)" become "sk2". */
    fun normalizeName(name: String): String {
        // NFKD also folds look-alike letters such as superscript "ᵛᴵᴾ ᴿᴬᵂ" into plain "VIP RAW".
        var s = Normalizer.normalize(name.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFKD).lowercase().replace(MARKS, "")
        s = s.replace(GROUP_PREFIX, "").replace(BRACKETS, " ").replace(REGION, " ").replace(QUALITY, " ")
        // "RTL 1" in the panel, "RTL" in the guide; "RTS 1" stays equal to "RTS 1" on both sides.
        s = s.trim().replace(TRAILING_ONE, "")
        // Same channel, different spelling: "Sport Klub 1" in the panel, "SK 1" in guides.
        return s.filter { it.isLetterOrDigit() }.replace("sportklub", "sk")
    }
}
