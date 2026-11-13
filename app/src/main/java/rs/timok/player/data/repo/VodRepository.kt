package rs.timok.player.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import rs.timok.player.data.db.WatchProgressDao
import rs.timok.player.data.db.WatchProgressEntity
import rs.timok.player.data.db.WatchlistDao
import rs.timok.player.data.db.WatchlistEntity
import rs.timok.player.data.source.PlaylistSource
import rs.timok.player.data.source.PlaylistSourceFactory
import rs.timok.player.domain.Episode
import rs.timok.player.domain.SourceCategory
import rs.timok.player.domain.VodDetails
import rs.timok.player.domain.VodItem
import rs.timok.player.domain.VodKind
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
/** What the VOD player should play, with enough context to save progress and find the next episode. */
data class VodPlayRequest(
    val kind: VodKind,
    /** Movie stream id or episode id. */
    val itemId: Long,
    val seriesId: Long?,
    val title: String,
    val poster: String?,
    val extension: String,
    val season: Int?,
    val episode: Int?,
    /** Episode title, shown under the series name in the player. */
    val episodeTitle: String? = null,
    /** Ready stream URL (catch-up); such items keep no resume point and have no next episode. */
    val url: String? = null,
)

/**
 * VOD lists come straight from the server and are cached in memory for the session:
 * they are large, change rarely and are only browsed one category at a time.
 */
@Singleton
class VodRepository @Inject constructor(
    private val playlists: PlaylistRepository,
    private val sources: PlaylistSourceFactory,
    private val progressDao: WatchProgressDao,
    private val watchlistDao: WatchlistDao,
) {
    private val mutex = Mutex()
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val categoryCache = HashMap<Pair<Long, VodKind>, List<SourceCategory>>()
    private val itemCache = HashMap<Triple<Long, VodKind, String>, List<VodItem>>()
    private val detailCache = HashMap<Triple<Long, VodKind, Long>, VodDetails>()

    suspend fun categories(kind: VodKind): List<SourceCategory> {
        val p = playlists.activePlaylistNow() ?: return emptyList()
        return mutex.withLock { categoryCache[p.id to kind] }
            ?: sources.create(p).vodCategories(kind).also { mutex.withLock { categoryCache[p.id to kind] = it } }
    }

    suspend fun items(kind: VodKind, categoryId: String): List<VodItem> {
        val p = playlists.activePlaylistNow() ?: return emptyList()
        val key = Triple(p.id, kind, categoryId)
        return mutex.withLock { itemCache[key] }
            ?: sources.create(p).vodItems(kind, categoryId).also { mutex.withLock { itemCache[key] = it } }
    }

    /** Every movie or series of the server (one big request, cached like a category), for search. */
    suspend fun allItems(kind: VodKind): List<VodItem> = items(kind, PlaylistSource.ALL_CATEGORIES)

    suspend fun details(kind: VodKind, id: Long): VodDetails {
        val p = playlists.activePlaylistNow() ?: error("no playlist")
        val key = Triple(p.id, kind, id)
        return mutex.withLock { detailCache[key] }
            ?: sources.create(p).vodDetails(kind, id).also { mutex.withLock { detailCache[key] = it } }
    }

    suspend fun streamUrl(request: VodPlayRequest): String? {
        val p = playlists.activePlaylistNow() ?: return null
        val source = sources.create(p)
        return if (request.kind == VodKind.MOVIE) source.movieUrl(request.itemId, request.extension)
        else source.episodeUrl(request.itemId, request.extension)
    }

    /** The episode after [current] in the same series, crossing into the next season. */
    suspend fun nextEpisode(current: VodPlayRequest): Episode? {
        val seriesId = current.seriesId ?: return null
        val all = details(VodKind.SERIES, seriesId).seasons.flatMap { it.episodes }
        val idx = all.indexOfFirst { it.id == current.itemId }
        return if (idx >= 0) all.getOrNull(idx + 1) else null
    }

    fun continueWatching(kind: VodKind, playlistId: Long?): Flow<List<WatchProgressEntity>> = when {
        playlistId == null -> emptyFlow()
        kind == VodKind.MOVIE -> progressDao.observeMovies(playlistId)
        else -> progressDao.observeSeries(playlistId)
    }

    fun movieProgress(playlistId: Long, movieId: Long): Flow<WatchProgressEntity?> =
        progressDao.observe(playlistId, "MOVIE", movieId)

    fun episodeProgress(playlistId: Long, seriesId: Long): Flow<List<WatchProgressEntity>> =
        progressDao.observeEpisodes(playlistId, seriesId)

    /** "My List" for [kind], newest first. */
    fun watchlist(kind: VodKind, playlistId: Long?): Flow<List<WatchlistEntity>> =
        if (playlistId == null) emptyFlow() else watchlistDao.observe(playlistId, kind.name)

    fun isOnWatchlist(playlistId: Long, kind: VodKind, itemId: Long): Flow<Boolean> =
        watchlistDao.observeContains(playlistId, kind.name, itemId)

    /** Adds the title to "My List" or removes it; returns true when it is now on the list. */
    suspend fun toggleWatchlist(details: VodDetails): Boolean {
        val p = playlists.activePlaylistNow() ?: return false
        return watchlistDao.toggle(
            WatchlistEntity(
                playlistId = p.id,
                kind = details.kind.name,
                itemId = details.id,
                title = details.name,
                poster = details.poster,
                rating = details.rating,
                year = details.year,
                extension = details.extension,
                addedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun progress(request: VodPlayRequest): WatchProgressEntity? {
        val p = playlists.activePlaylistNow() ?: return null
        return progressDao.get(p.id, progressKind(request), request.itemId)
    }

    suspend fun saveProgress(request: VodPlayRequest, positionMs: Long, durationMs: Long) {
        val p = playlists.activePlaylistNow() ?: return
        if (durationMs <= 0 || positionMs < MIN_SAVE_MS) return
        progressDao.upsert(
            WatchProgressEntity(
                playlistId = p.id,
                kind = progressKind(request),
                itemId = request.itemId,
                seriesId = request.seriesId,
                title = request.title,
                poster = request.poster,
                extension = request.extension,
                season = request.season,
                episode = request.episode,
                positionMs = positionMs,
                durationMs = durationMs,
                finished = positionMs >= durationMs * FINISHED_FRACTION,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Saves even when the caller (a closing screen) is being destroyed. */
    fun saveProgressLater(request: VodPlayRequest, positionMs: Long, durationMs: Long) {
        saveScope.launch { saveProgress(request, positionMs, durationMs) }
    }

    fun clearCache() {
        categoryCache.clear()
        itemCache.clear()
        detailCache.clear()
    }

    private fun progressKind(r: VodPlayRequest) = if (r.kind == VodKind.MOVIE) "MOVIE" else "EPISODE"

    companion object {
        /** Below this nothing is saved: the viewer was just peeking. */
        const val MIN_SAVE_MS = 30_000L
        const val FINISHED_FRACTION = 0.95
    }
}
