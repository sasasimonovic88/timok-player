package rs.timok.player.data.source

import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import rs.timok.player.data.remote.XtreamApi
import android.util.Base64
import rs.timok.player.domain.AccountInfo
import rs.timok.player.domain.ArchiveProgram
import rs.timok.player.domain.SourceCategory
import rs.timok.player.domain.SourceChannel
import rs.timok.player.domain.SourceException
import rs.timok.player.domain.StreamFormat
import rs.timok.player.domain.Episode
import rs.timok.player.domain.Season
import rs.timok.player.domain.VodDetails
import rs.timok.player.domain.VodItem
import rs.timok.player.domain.VodKind
import rs.timok.player.data.remote.arr
import rs.timok.player.data.remote.image
import rs.timok.player.data.remote.int
import rs.timok.player.data.remote.long
import rs.timok.player.data.remote.obj
import rs.timok.player.data.remote.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import android.util.Log
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.URLEncoder
import java.net.UnknownHostException

@OptIn(ExperimentalSerializationApi::class)
class XtreamSource(
    private val api: XtreamApi,
    private val json: Json,
    server: String,
    private val username: String,
    private val password: String,
) : PlaylistSource {

    private val baseUrl = normalizeServer(server)
    private val apiUrl = "$baseUrl/player_api.php"

    override suspend fun authenticate(): AccountInfo = call {
        val user = api.authenticate(apiUrl, username, password).userInfo
            ?: throw SourceException.InvalidCredentials()
        if (user.auth?.trim() == "0") throw SourceException.InvalidCredentials()
        val status = user.status?.trim().orEmpty().ifEmpty { "Active" }
        val expiresAt = user.expDate?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)
        if (status.equals("Expired", true) || (expiresAt != null && expiresAt < System.currentTimeMillis())) {
            throw SourceException.AccountExpired()
        }
        if (!status.equals("Active", true)) throw SourceException.AccountDisabled(status)
        AccountInfo(
            status = status,
            expiresAt = expiresAt,
            maxConnections = user.maxConnections?.trim()?.toIntOrNull() ?: 1,
        )
    }

    override suspend fun liveCategories(): List<SourceCategory> = call {
        api.liveCategories(apiUrl, username, password).mapIndexedNotNull { i, dto ->
            val id = dto.categoryId?.trim().orEmpty()
            if (id.isEmpty()) null else SourceCategory(id, dto.categoryName?.trim().orEmpty().ifEmpty { id }, i)
        }
    }

    override suspend fun liveChannels(): List<SourceChannel> = call {
        api.liveStreams(apiUrl, username, password).mapIndexedNotNull { i, dto ->
            val id = dto.streamId?.trim()?.toLongOrNull() ?: return@mapIndexedNotNull null
            SourceChannel(
                streamId = id,
                number = dto.num?.trim()?.toDoubleOrNull()?.toInt() ?: (i + 1),
                name = dto.name?.trim().orEmpty().ifEmpty { id.toString() },
                logo = dto.streamIcon?.trim()?.takeIf { it.startsWith("http") },
                categoryId = dto.categoryId?.trim()?.takeIf { it.isNotEmpty() },
                epgChannelId = dto.epgChannelId?.trim()?.takeIf { it.isNotEmpty() },
                hasArchive = dto.tvArchive?.trim() == "1",
                order = i,
            )
        }
    }

    override fun liveStreamUrl(streamId: Long, format: StreamFormat): String =
        "$baseUrl/live/${enc(username)}/${enc(password)}/$streamId.${format.extension}"

    override fun epgUrl(): String = "$baseUrl/xmltv.php?username=${enc(username)}&password=${enc(password)}"

    override suspend fun vodCategories(kind: VodKind): List<SourceCategory> = call {
        val action = if (kind == VodKind.MOVIE) "get_vod_categories" else "get_series_categories"
        api.action(apiUrl, username, password, action).arr().orEmpty().mapIndexedNotNull { i, e ->
            val o = e.obj() ?: return@mapIndexedNotNull null
            val id = o.str("category_id") ?: return@mapIndexedNotNull null
            SourceCategory(id, o.str("category_name") ?: id, i)
        }
    }

    override suspend fun vodItems(kind: VodKind, categoryId: String): List<VodItem> = call {
        val action = if (kind == VodKind.MOVIE) "get_vod_streams" else "get_series"
        val extra = if (categoryId == PlaylistSource.ALL_CATEGORIES) emptyMap() else mapOf("category_id" to categoryId)
        // Streamed one item at a time: a whole server can list tens of thousands of movies,
        // and the full JSON tree would not fit in the heap of a small TV box.
        val body = api.actionStream(apiUrl, username, password, action, extra)
        withContext(Dispatchers.IO) {
            body.use { b -> b.byteStream().buffered().use { stream -> jsonArrayItems(stream).mapNotNull { e -> vodItem(kind, e) }.toList() } }
        }
    }

    /** Elements of a top-level JSON array; anything else (an error object, empty body) gives none. */
    private fun jsonArrayItems(stream: java.io.BufferedInputStream): Sequence<JsonElement> {
        stream.mark(64)
        var c = stream.read()
        while (c != -1 && c.toChar().isWhitespace()) c = stream.read()
        stream.reset()
        if (c != '['.code) return emptySequence()
        return json.decodeToSequence(stream, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
    }

    private fun vodItem(kind: VodKind, e: JsonElement): VodItem? {
        val o = e.obj() ?: return null
        val id = (if (kind == VodKind.MOVIE) o.long("stream_id") else o.long("series_id")) ?: return null
        return VodItem(
            kind = kind,
            id = id,
            name = o.str("name", "title") ?: id.toString(),
            poster = o.image("stream_icon", "cover"),
            rating = o.str("rating")?.takeIf { it != "0" },
            year = o.str("year", "releaseDate", "release_date")?.take(4),
            extension = o.str("container_extension"),
            categoryId = o.str("category_id"),
            added = o.str("added", "last_modified")?.toLongOrNull(),
        )
    }

    override suspend fun vodDetails(kind: VodKind, id: Long): VodDetails = call {
        if (kind == VodKind.MOVIE) movieDetails(id) else seriesDetails(id)
    }

    private suspend fun movieDetails(id: Long): VodDetails {
        val root = api.action(apiUrl, username, password, "get_vod_info", mapOf("vod_id" to id.toString())).obj()
            ?: throw SourceException.BadResponse(null)
        val info = root["info"].obj() ?: JsonObject(emptyMap())
        val data = root["movie_data"].obj() ?: JsonObject(emptyMap())
        return VodDetails(
            kind = VodKind.MOVIE,
            id = id,
            name = data.str("name") ?: info.str("name", "o_name") ?: id.toString(),
            poster = info.image("movie_image", "cover_big"),
            backdrop = info.image("backdrop_path"),
            plot = info.str("plot", "description"),
            genre = info.str("genre"),
            cast = info.str("cast", "actors"),
            director = info.str("director"),
            year = info.str("releasedate", "release_date", "year")?.take(4),
            rating = info.str("rating")?.takeIf { it != "0" },
            durationSecs = info.int("duration_secs"),
            extension = data.str("container_extension") ?: "mp4",
        )
    }

    private suspend fun seriesDetails(id: Long): VodDetails {
        val root = api.action(apiUrl, username, password, "get_series_info", mapOf("series_id" to id.toString())).obj()
            ?: throw SourceException.BadResponse(null)
        val info = root["info"].obj() ?: JsonObject(emptyMap())
        val seasonNames = root["seasons"].arr().orEmpty().mapNotNull { it.obj() }
            .associate { (it.int("season_number") ?: 0) to it.str("name") }
        // "episodes" is usually an object keyed by season number, sometimes an array of arrays.
        val groups: List<Pair<Int?, List<JsonObject>>> = when (val eps = root["episodes"]) {
            is JsonObject -> eps.entries.map { (k, v) -> k.toIntOrNull() to v.arr().orEmpty().mapNotNull { it.obj() } }
            is JsonArray -> eps.map { null to it.arr().orEmpty().mapNotNull { e -> e.obj() } }
            else -> emptyList()
        }
        val seasons = groups.mapNotNull { (key, list) ->
            val episodes = list.mapNotNull { e ->
                val epId = e.long("id") ?: return@mapNotNull null
                val epInfo = e["info"].obj() ?: JsonObject(emptyMap())
                Episode(
                    id = epId,
                    season = e.int("season") ?: key ?: 1,
                    number = e.int("episode_num") ?: 0,
                    title = cleanEpisodeTitle(e.str("title"), info.str("name")),
                    plot = epInfo.str("plot"),
                    image = epInfo.image("movie_image"),
                    durationSecs = epInfo.int("duration_secs"),
                    extension = e.str("container_extension") ?: "mp4",
                )
            }.sortedBy { it.number }
            if (episodes.isEmpty()) return@mapNotNull null
            val number = key ?: episodes.first().season
            Season(number, seasonNames[number] ?: "", episodes)
        }.sortedBy { it.number }
        return VodDetails(
            kind = VodKind.SERIES,
            id = id,
            name = info.str("name") ?: id.toString(),
            poster = info.image("cover"),
            backdrop = info.image("backdrop_path"),
            plot = info.str("plot"),
            genre = info.str("genre"),
            cast = info.str("cast"),
            director = info.str("director"),
            year = info.str("releaseDate", "release_date", "year")?.take(4),
            rating = info.str("rating")?.takeIf { it != "0" },
            durationSecs = null,
            extension = null,
            seasons = seasons,
        )
    }

    override fun movieUrl(id: Long, extension: String): String =
        "$baseUrl/movie/${enc(username)}/${enc(password)}/$id.$extension"

    override fun episodeUrl(id: Long, extension: String): String =
        "$baseUrl/series/${enc(username)}/${enc(password)}/$id.$extension"

    override suspend fun archive(streamId: Long): List<ArchiveProgram> = call {
        val root = api.action(apiUrl, username, password, "get_simple_data_table", mapOf("stream_id" to streamId.toString())).obj()
        val listings = root?.get("epg_listings").arr().orEmpty().mapNotNull { it.obj() }
        val now = System.currentTimeMillis()
        // Panels mark archived entries with has_archive; older ones without the field get every past entry.
        val flagged = listings.any { it.containsKey("has_archive") }
        listings.mapNotNull { o ->
            if (flagged && o.int("has_archive") != 1) return@mapNotNull null
            val start = o.long("start_timestamp")?.times(1000) ?: return@mapNotNull null
            val end = o.long("stop_timestamp")?.times(1000) ?: return@mapNotNull null
            val serverStart = o.str("start")?.takeIf { it.length >= 16 } ?: return@mapNotNull null
            if (start >= now || end <= start) return@mapNotNull null
            ArchiveProgram(
                title = decode(o.str("title")) ?: return@mapNotNull null,
                description = decode(o.str("description")),
                startMs = start,
                endMs = end,
                serverStart = serverStart,
            )
        }.distinctBy { it.startMs }.sortedByDescending { it.startMs }
    }

    /** {server}/timeshift/{user}/{pass}/{minutes}/{YYYY-MM-DD:HH-MM}/{id}.ts, start in the server's time zone. */
    override fun catchupUrl(streamId: Long, program: ArchiveProgram): String {
        val minutes = ((program.endMs - program.startMs) / 60_000).coerceAtLeast(1)
        val start = program.serverStart.take(16).replace(' ', ':').replace(Regex("(\\d{2}):(\\d{2})$"), "$1-$2")
        return "$baseUrl/timeshift/${enc(username)}/${enc(password)}/$minutes/$start/$streamId.ts"
    }

    /** EPG texts from player_api are Base64; some panels send plain text. */
    private fun decode(value: String?): String? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { String(Base64.decode(v, Base64.DEFAULT), Charsets.UTF_8) }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it.none { c -> c == '\uFFFD' } } ?: v
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * Some panels drop or refuse a request now and then (a 5xx, a reset connection, a half-sent body).
     * Those are retried a couple of times; wrong credentials and an expired account are not.
     */
    private suspend fun <T> call(block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return attempt(block)
            } catch (e: SourceException) {
                val transient = e is SourceException.ServerUnreachable || e is SourceException.BadResponse
                // Type and HTTP code only: the request URL carries the credentials.
                Log.w(TAG, "player_api attempt $attempt failed: ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}" +
                    ((e.cause as? HttpException)?.let { " HTTP ${it.code()}" } ?: ""))
                if (!transient || attempt >= MAX_ATTEMPTS) throw e
                delay(RETRY_DELAY_MS * attempt)
                attempt++
            }
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T = try {
        block()
    } catch (e: SourceException) {
        throw e
    } catch (e: HttpException) {
        if (e.code() == 401 || e.code() == 403) throw SourceException.InvalidCredentials()
        throw SourceException.ServerUnreachable(e)
    } catch (e: UnknownHostException) {
        throw SourceException.ServerUnreachable(e)
    } catch (e: IOException) {
        throw SourceException.ServerUnreachable(e)
    } catch (e: SerializationException) {
        throw SourceException.BadResponse(e)
    } catch (e: IllegalArgumentException) {
        // Malformed URL or unexpected JSON shape.
        throw SourceException.BadResponse(e)
    }

    companion object {
        private const val TAG = "XtreamSource"
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1_000L

        /** Panels often repeat the series name and "S01E01" in every episode title; keep only the episode part. */
        fun cleanEpisodeTitle(raw: String?, seriesName: String?): String {
            var t = raw?.trim().orEmpty()
            if (seriesName != null && t.startsWith(seriesName, ignoreCase = true)) t = t.substring(seriesName.length)
            t = t.replace(Regex("^[\\s\\-–:|]*S\\d{1,2}\\s*E\\d{1,3}[\\s\\-–:|]*", RegexOption.IGNORE_CASE), "")
            return t.trim().trim('-', '–', ':', '|').trim()
        }

        /** Accepts `host:port`, `http://host:port` or a full URL with trailing slash/path. */
        fun normalizeServer(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            s = s.removeSuffix("/player_api.php")
            return s.trimEnd('/')
        }
    }
}
