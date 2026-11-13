package rs.timok.player.epg

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Streaming XMLTV parser. Never builds a DOM: the guide can be hundreds of MB.
 * Programmes are matched by their channel id; `<channel>` entries are only read for their
 * display names, so guide channels with other ids can still be matched to channels by name.
 */
class XmltvParser {

    class Program(
        val channelKey: String,
        val startMs: Long,
        val endMs: Long,
        val title: String,
        val description: String?,
    )

    /**
     * @param wantedKeys lower-cased channel ids to keep; programmes for other channels are skipped.
     * @param nameKeys normalized channel name ([EpgKeys.normalizeName]) to the keys programmes are stored under,
     *   for guide channels whose id is not wanted. Panels often list one channel several times (per region,
     *   backup streams), so a name can stand for several keys.
     * @param skipKeys channel keys already filled by an earlier guide; their programmes here are ignored.
     * @param offsetMs added to every parsed time (per-playlist EPG shift).
     * @param keyCountries country of the channel behind each key ([EpgKeys.countryOf]); a name match is only made
     *   with a guide channel from a compatible country, so a Croatian channel never gets a Romanian guide.
     * @param guideCountry country of the whole guide (a built-in per-country guide), or null to read it from each
     *   guide channel id.
     */
    suspend fun parse(
        input: InputStream,
        wantedKeys: Set<String>,
        nameKeys: Map<String, List<String>>,
        skipKeys: Set<String> = emptySet(),
        keyCountries: Map<String, String> = emptyMap(),
        guideCountry: String? = null,
        offsetMs: Long,
        windowStart: Long,
        windowEnd: Long,
        onProgram: suspend (Program) -> Unit,
    ) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(maybeGunzip(input), null)

        // Guide channel id -> display names, collected from <channel> entries (they precede the programmes).
        val guideNames = HashMap<String, List<String>>()
        // Wanted ids the guide really lists; those channels need no name match.
        val directIds = HashSet<String>()
        var aliases: Map<String, List<String>>? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "channel") {
                val id = parser.getAttributeValue(null, "id")?.trim()?.lowercase()
                val names = readDisplayNames(parser)
                if (id != null && id in wantedKeys) directIds += id
                else if (id != null && names.isNotEmpty()) guideNames[id] = names
            } else if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val map = aliases ?: resolveAliases(guideNames, directIds + skipKeys, nameKeys, keyCountries, guideCountry).also { aliases = it }
                val raw = parser.getAttributeValue(null, "channel")?.trim()?.lowercase()
                val keys = when {
                    raw == null -> emptyList()
                    raw in wantedKeys -> listOf(raw)
                    else -> map[raw].orEmpty()
                }.filter { it !in skipKeys }
                val start = parser.getAttributeValue(null, "start")?.let(::parseTime)
                val stop = parser.getAttributeValue(null, "stop")?.let(::parseTime)
                if (keys.isEmpty() || start == null || stop == null) {
                    skip(parser)
                } else {
                    val s = start + offsetMs
                    val e = stop + offsetMs
                    val (title, desc) = readProgrammeBody(parser)
                    if (e > windowStart && s < windowEnd && title != null) {
                        for (key in keys) onProgram(Program(key, s, e, title, desc))
                    }
                }
            }
            event = parser.next()
        }
    }

    /**
     * Maps guide ids to channel keys by display name. A channel key gets at most one guide channel, and never one
     * that already has a direct id match, so programmes are not stored twice.
     */
    private fun resolveAliases(
        guideNames: Map<String, List<String>>,
        directIds: Set<String>,
        nameKeys: Map<String, List<String>>,
        keyCountries: Map<String, String>,
        guideCountry: String?,
    ): Map<String, List<String>> {
        if (nameKeys.isEmpty()) return emptyMap()
        val taken = HashSet<String>(directIds)
        val result = HashMap<String, List<String>>()
        // Exact names first; then, for channels still without a guide, names without a "Channel" word
        // ("Discovery Channel" in the guide, "Discovery" in the panel), so "RTL Channel" never beats "RTL".
        for (loose in listOf(false, true)) {
            for ((id, names) in guideNames) {
                if (id in result) continue
                val candidates = names.firstNotNullOfOrNull { name ->
                    val normalized = EpgKeys.normalizeName(name)
                    if (loose) normalized.replace(CHANNEL_WORD, "").takeIf { it != normalized }?.let { nameKeys[it] }
                    else nameKeys[normalized]
                } ?: continue
                val country = guideCountry ?: EpgKeys.countryOfGuideId(id)
                val keys = candidates.filter { EpgKeys.compatible(keyCountries[it], country) && taken.add(it) }
                if (keys.isNotEmpty()) result[id] = keys
            }
        }
        return result
    }

    /** Reads the `<display-name>` children of a `<channel>`; leaves the parser on `</channel>`. */
    private fun readDisplayNames(parser: XmlPullParser): List<String> {
        val names = ArrayList<String>(2)
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> if (parser.name == "display-name") {
                    readText(parser).trim().takeIf { it.isNotEmpty() }?.let(names::add)
                } else depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return names
            }
        }
        return names
    }

    /** Reads the first `<title>` and `<desc>` children; leaves the parser on `</programme>`. */
    private fun readProgrammeBody(parser: XmlPullParser): Pair<String?, String?> {
        var title: String? = null
        var desc: String? = null
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "title" -> {
                            val t = readText(parser)
                            if (title == null && t.isNotBlank()) title = t.trim()
                        }
                        "desc" -> {
                            val t = readText(parser)
                            if (desc == null && t.isNotBlank()) desc = t.trim()
                        }
                        else -> depth++
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return title to desc
            }
        }
        return title to desc
    }

    /** Reads text content and leaves the parser on the element's end tag. */
    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT -> sb.append(parser.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString()
    }

    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun maybeGunzip(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024)) else buffered
    }

    companion object {
        private val CHANNEL_WORD = Regex("channel")

        /**
         * Parses XMLTV times like `20240131203000 +0100`. Missing zone means UTC.
         * Done by hand: it runs hundreds of thousands of times per import.
         */
        fun parseTime(raw: String): Long? {
            val s = raw.trim()
            if (s.length < 12) return null
            fun num(from: Int, len: Int): Int? {
                if (from + len > s.length) return null
                var v = 0
                for (i in from until from + len) {
                    val c = s[i]
                    if (c !in '0'..'9') return null
                    v = v * 10 + (c - '0')
                }
                return v
            }
            val year = num(0, 4) ?: return null
            val month = num(4, 2) ?: return null
            val day = num(6, 2) ?: return null
            val hour = num(8, 2) ?: return null
            val minute = num(10, 2) ?: return null
            val second = num(12, 2) ?: 0
            var utc = epochDay(year, month, day) * 86_400_000L +
                hour * 3_600_000L + minute * 60_000L + second * 1000L

            val signIdx = s.indexOfAny(charArrayOf('+', '-'), startIndex = 12)
            if (signIdx >= 0) {
                val sign = if (s[signIdx] == '-') -1 else 1
                val zh = num(signIdx + 1, 2) ?: 0
                val zm = num(signIdx + 3, 2) ?: 0
                utc -= sign * (zh * 3_600_000L + zm * 60_000L)
            }
            return utc
        }

        /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
        private fun epochDay(y0: Int, m: Int, d: Int): Long {
            val y = if (m <= 2) y0 - 1 else y0
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val mp = (m + 9) % 12
            val doy = (153 * mp + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097L + doe - 719_468L
        }
    }
}
