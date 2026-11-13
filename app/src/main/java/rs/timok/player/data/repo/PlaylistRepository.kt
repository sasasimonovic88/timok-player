package rs.timok.player.data.repo

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import rs.timok.player.domain.ArchiveProgram
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import rs.timok.player.data.db.AppDatabase
import rs.timok.player.data.db.CategoryEntity
import rs.timok.player.data.db.ChannelEntity
import rs.timok.player.data.db.PlaylistEntity
import rs.timok.player.data.settings.SettingsStore
import rs.timok.player.data.source.PlaylistSourceFactory
import rs.timok.player.data.source.XtreamSource
import rs.timok.player.domain.SourceException
import rs.timok.player.domain.StreamFormat
import rs.timok.player.epg.EpgWorker
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val sources: PlaylistSourceFactory,
    private val settings: SettingsStore,
) {
    private val playlistDao = db.playlistDao()
    private val channelDao = db.channelDao()

    /** The playlist currently in use, falling back to the first stored one. */
    val activePlaylist: Flow<PlaylistEntity?> =
        combine(settings.activePlaylistId, playlistDao.observeAll()) { id, all ->
            all.firstOrNull { it.id == id } ?: all.firstOrNull()
        }

    suspend fun activePlaylistNow(): PlaylistEntity? = activePlaylist.first()

    /** Validates the account, stores it, loads channels and schedules the guide download. */
    suspend fun signIn(server: String, username: String, password: String): PlaylistEntity {
        requireNetwork()
        val source = sources.xtream(server, username, password)
        val account = source.authenticate()
        val normalized = XtreamSource.normalizeServer(server)
        val id = playlistDao.insert(
            PlaylistEntity(
                name = normalized.substringAfter("://"),
                server = normalized,
                username = username,
                password = password,
                status = account.status,
                expiresAt = account.expiresAt,
                maxConnections = account.maxConnections,
            )
        )
        try {
            refreshChannels(id)
        } catch (e: Exception) {
            // Do not keep a half-created playlist around.
            deletePlaylist(id)
            throw e
        }
        settings.setActivePlaylist(id)
        EpgWorker.enqueue(context, id, force = true)
        return playlistDao.get(id)!!
    }

    /**
     * Moves the account to a new server address (providers change domains). The new address is
     * checked with the stored credentials first; favorites survive because stream ids stay the same.
     */
    suspend fun changeServer(playlistId: Long, server: String) {
        requireNetwork()
        val playlist = playlistDao.get(playlistId) ?: return
        val account = sources.xtream(server, playlist.username, playlist.password).authenticate()
        val normalized = XtreamSource.normalizeServer(server)
        val oldName = playlist.server.substringAfter("://")
        playlistDao.update(
            playlist.copy(
                server = normalized,
                // Only replace a name that was derived from the old address.
                name = if (playlist.name == oldName) normalized.substringAfter("://") else playlist.name,
                status = account.status,
                expiresAt = account.expiresAt,
                maxConnections = account.maxConnections,
            )
        )
        refreshChannels(playlistId)
    }

    /** Re-checks the account and reloads categories and channels from the server. */
    suspend fun refreshChannels(playlistId: Long) {
        requireNetwork()
        val playlist = playlistDao.get(playlistId) ?: return
        val source = sources.create(playlist)
        val account = source.authenticate()
        val categories = source.liveCategories()
        val channels = source.liveChannels()
        if (channels.isEmpty()) throw SourceException.EmptyList()
        channelDao.replaceAll(
            playlistId,
            categories.map { CategoryEntity(playlistId, it.id, it.name, it.order) },
            channels.map {
                ChannelEntity(
                    playlistId = playlistId,
                    streamId = it.streamId,
                    number = it.number,
                    name = it.name,
                    logo = it.logo,
                    categoryId = it.categoryId,
                    epgChannelId = it.epgChannelId?.lowercase(),
                    hasArchive = it.hasArchive,
                    sortOrder = it.order,
                )
            },
        )
        playlistDao.update(
            playlist.copy(
                status = account.status,
                expiresAt = account.expiresAt,
                maxConnections = account.maxConnections,
                channelsUpdatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun deletePlaylist(id: Long) {
        db.withTransaction {
            db.favoriteDao().deleteAll(id)
            db.watchProgressDao().deleteAll(id)
            db.watchlistDao().deleteAll(id)
            db.programDao().deleteForPlaylist(id)
            channelDao.deleteChannels(id)
            channelDao.deleteCategories(id)
            channelDao.deleteHidden(id)
            playlistDao.delete(id)
        }
        settings.setActivePlaylist(null)
    }

    fun streamUrl(playlist: PlaylistEntity, streamId: Long, format: StreamFormat): String =
        sources.create(playlist).liveStreamUrl(streamId, format)

    suspend fun archive(streamId: Long): List<ArchiveProgram> {
        val playlist = activePlaylistNow() ?: return emptyList()
        return sources.create(playlist).archive(streamId)
    }

    suspend fun catchupUrl(streamId: Long, program: ArchiveProgram): String? {
        val playlist = activePlaylistNow() ?: return null
        return sources.create(playlist).catchupUrl(streamId, program)
    }

    fun preferredFormat(playlist: PlaylistEntity): StreamFormat =
        runCatching { StreamFormat.valueOf(playlist.streamFormat) }.getOrDefault(StreamFormat.HLS)

    private val staleRefresh = Mutex()

    /**
     * Reloads the channel list once a day and schedules the guide when it is old. Called by the periodic
     * [rs.timok.player.work.RefreshWorker] and whenever the app comes back to the screen, since a TV box keeps
     * the app alive for days without a fresh start. A failed list refresh keeps the old list.
     */
    suspend fun refreshIfStale() {
        if (!staleRefresh.tryLock()) return
        try {
            val playlist = activePlaylistNow() ?: return
            if (System.currentTimeMillis() - (playlist.channelsUpdatedAt ?: 0) > CHANNELS_MAX_AGE_MS) {
                runCatching { refreshChannels(playlist.id) }
                    .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Channel refresh failed: ${it.javaClass.simpleName}") }
            }
            refreshEpgIfStale(playlist)
        } finally {
            staleRefresh.unlock()
        }
    }

    /** Schedules a guide refresh when it is older than [maxAgeMs]. */
    fun refreshEpgIfStale(playlist: PlaylistEntity, maxAgeMs: Long = EPG_MAX_AGE_MS) {
        val updated = playlist.epgUpdatedAt ?: 0
        if (System.currentTimeMillis() - updated > maxAgeMs) EpgWorker.enqueue(context, playlist.id, force = false)
    }

    suspend fun setEpgUrl(playlistId: Long, url: String?) = playlistDao.setEpgUrl(playlistId, url)

    suspend fun clearEpgError(playlistId: Long) = playlistDao.setEpgError(playlistId, null)

    fun refreshEpgNow(playlistId: Long) = EpgWorker.enqueue(context, playlistId, force = true)

    private fun requireNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            throw SourceException.NoNetwork()
        }
    }

    private companion object {
        const val TAG = "PlaylistRepository"
        const val CHANNELS_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        // A little under the 12 h worker period, so every periodic run finds the guide due.
        const val EPG_MAX_AGE_MS = 11 * 60 * 60 * 1000L
    }
}
