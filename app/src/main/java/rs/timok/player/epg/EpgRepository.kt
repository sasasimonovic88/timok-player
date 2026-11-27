package rs.timok.player.epg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import rs.timok.player.data.db.ChannelDao
import rs.timok.player.data.db.PlaylistDao
import rs.timok.player.data.db.ProgramDao
import rs.timok.player.data.db.ProgramEntity
import rs.timok.player.data.source.PlaylistSourceFactory
import rs.timok.player.data.settings.AppPrefs
import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpgRepository @Inject constructor(
    private val okHttp: OkHttpClient,
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val programDao: ProgramDao,
    private val sources: PlaylistSourceFactory,
) {
    /** Downloads and imports the XMLTV guide for a playlist. Returns the number of stored programmes. */
    suspend fun refresh(playlistId: Long): Int = withContext(Dispatchers.IO) {
        val playlist = playlistDao.get(playlistId) ?: return@withContext 0
        // Guide links set in Settings win (several may be given): some panels serve no guide of their own.
        val urls = playlist.epgUrl?.split(LINK_SEPARATORS)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            .ifEmpty { listOfNotNull(sources.create(playlist).epgUrl()) }
        // Checked built-in guides fill the channels those leave without a guide (or all of them, when the panel has none).
        val fallback = EpgPresets.all.filter { it.id in AppPrefs.epgPresets.value }
        val ownCountries = fallback.mapNotNull { it.country }.toSet()
        if (urls.isEmpty() && fallback.isEmpty()) return@withContext 0
        val wanted = channelDao.epgChannelIds(playlistId).toHashSet()
        // Every channel can also be matched by name, under the same key the channel list looks up.
        val nameKeys = HashMap<String, MutableList<String>>()
        // Country per key; a key shared by channels of different countries gets none and matches any guide.
        val keyCountries = HashMap<String, String>()
        val mixedKeys = HashSet<String>()
        for (c in channelDao.epgNames(playlistId)) {
            val name = EpgKeys.normalizeName(c.name)
            if (name.isEmpty()) continue
            val keys = nameKeys.getOrPut(name) { ArrayList(1) }
            val key = EpgKeys.key(c.epgChannelId, c.name)
            if (key !in keys) keys += key
            val country = EpgKeys.countryOf(c.name)
            if (country == null || keyCountries.getOrPut(key) { country } != country) mixedKeys += key
        }
        mixedKeys.forEach(keyCountries::remove)
        if (wanted.isEmpty() && nameKeys.isEmpty()) return@withContext 0

        val now = System.currentTimeMillis()
        val windowStart = now - KEEP_PAST_MS
        val windowEnd = now + KEEP_FUTURE_MS
        // New rows go under a temporary id and are swapped in at the end, so the old guide stays visible meanwhile.
        val tempId = -playlistId
        programDao.deleteForPlaylist(tempId)

        // The guide can take minutes to download on slow panels.
        val client = okHttp.newBuilder().readTimeout(3, TimeUnit.MINUTES).build()
        var count = 0
        // Channels filled by an earlier link are not taken again from a later one...
        val filled = HashSet<String>()
        // ...except those filled only from a neighbour country's guide: an own-country guide later replaces them.
        val weakFilled = HashSet<String>()
        var lastError: Exception? = null
        suspend fun importLink(url: String, country: String?, index: Int, total: Int) {
            val keys = HashSet<String>()
            val weak = HashSet<String>()
            // Weak keys this link fills properly: their stand-in rows go before the new ones are added.
            val replaced = HashSet<String>()
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("EPG HTTP ${response.code}")
                    val batch = ArrayList<ProgramEntity>(BATCH)
                    XmltvParser().parse(
                        input = response.body!!.byteStream(),
                        wantedKeys = wanted,
                        nameKeys = nameKeys,
                        skipKeys = filled - weakFilled,
                        keyCountries = keyCountries,
                        guideCountry = country,
                        ownCountries = ownCountries,
                        weakKeys = weak,
                        offsetMs = playlist.epgOffsetHours * 3_600_000L,
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                    ) { p ->
                        if (p.channelKey in weakFilled) {
                            // Another stand-in never replaces the first one.
                            if (p.channelKey in weak) return@parse
                            if (replaced.add(p.channelKey)) {
                                programDao.insert(batch)
                                count += batch.size
                                batch.clear()
                                programDao.deleteForChannel(tempId, p.channelKey)
                            }
                        }
                        keys += p.channelKey
                        batch += ProgramEntity(
                            playlistId = tempId,
                            channelKey = p.channelKey,
                            startMs = p.startMs,
                            endMs = p.endMs,
                            title = p.title,
                            description = p.description,
                        )
                        if (batch.size >= BATCH) {
                            programDao.insert(batch)
                            count += batch.size
                            batch.clear()
                        }
                    }
                    if (batch.isNotEmpty()) {
                        programDao.insert(batch)
                        count += batch.size
                    }
                }
                filled += keys
                weakFilled -= replaced
                weakFilled += keys.filter { it in weak && it !in filled - keys }
            } catch (e: CancellationException) {
                // Replaced by a newer download: stop, and do not report it as a broken link.
                programDao.deleteForPlaylist(tempId)
                throw e
            } catch (e: Exception) {
                // One broken link must not throw away what the others delivered.
                lastError = e
                Log.w(TAG, "Guide link $index/$total failed: ${e.message}")
            }
        }
        urls.forEachIndexed { i, url -> importLink(url, null, i + 1, urls.size) }
        if (fallback.isNotEmpty()) {
            Log.i(TAG, "${filled.size} channels from the guide links; filling the rest from ${fallback.size} built-in guide(s)")
            fallback.forEachIndexed { i, preset -> importLink(preset.url, preset.country, i + 1, fallback.size) }
        }
        val error = lastError
        if (count == 0 && error != null) {
            programDao.deleteForPlaylist(tempId)
            playlistDao.setEpgError(playlistId, error.message ?: error.javaClass.simpleName)
            throw error
        }
        if (count == 0) {
            // Keep the old guide rather than replacing it with nothing (wrong link, no channel matched).
            programDao.deleteForPlaylist(tempId)
            playlistDao.setEpgError(playlistId, NO_MATCH)
            throw IOException(NO_MATCH)
        }
        programDao.swapIn(playlistId, tempId)
        playlistDao.setEpgUpdated(playlistId, System.currentTimeMillis())
        count
    }

    companion object {
        /** [rs.timok.player.data.db.PlaylistEntity.epgError] when the guide has no programmes for any channel. */
        const val NO_MATCH = "no-match"
        private const val TAG = "EpgRepository"
        private val LINK_SEPARATORS = Regex("[\\s,;]+")
        private const val BATCH = 1000
        private const val KEEP_PAST_MS = 3 * 60 * 60 * 1000L
        private const val KEEP_FUTURE_MS = 3 * 24 * 60 * 60 * 1000L
    }
}
