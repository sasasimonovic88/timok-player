package rs.timok.player.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rs.timok.player.BuildConfig
import rs.timok.player.data.db.AppDatabase
import rs.timok.player.data.db.FavoriteEntity
import rs.timok.player.data.db.HiddenCategoryEntity
import rs.timok.player.data.db.LockedCategoryEntity
import rs.timok.player.data.db.WatchProgressEntity
import rs.timok.player.data.db.WatchlistEntity
import rs.timok.player.data.repo.PlaylistRepository
import rs.timok.player.data.settings.AppPrefs
import rs.timok.player.data.settings.SettingsStore
import rs.timok.player.domain.VodSort
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backup file: what the viewer built up in the app, so it survives a new box, a reinstall or the switch to a
 * build signed with another key. Account credentials are deliberately left out (the file may sit in Downloads or
 * on a USB stick); the viewer signs in again, then restores.
 */
@Serializable
data class BackupFile(
    val app: String = APP_ID,
    val format: Int = FORMAT,
    val appVersion: String = "",
    val exportedAt: Long = 0,
    /** In the manual order; the name lets a restore find the channel when stream ids differ (other server). */
    val favorites: List<BackupChannel> = emptyList(),
    val hiddenCategories: List<String> = emptyList(),
    /** Categories behind the parental PIN (not the PIN itself); null in backups made before 0.5.7: kept as they are. */
    val lockedCategories: List<String>? = null,
    val watchlist: List<BackupWatchlist> = emptyList(),
    val progress: List<BackupProgress> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
    val account: BackupAccountSettings = BackupAccountSettings(),
) {
    companion object {
        const val APP_ID = "TIMOK-Player"
        const val FORMAT = 1
    }
}

@Serializable
data class BackupChannel(val streamId: Long, val name: String)

@Serializable
data class BackupWatchlist(
    val kind: String,
    val itemId: Long,
    val title: String,
    val poster: String? = null,
    val rating: String? = null,
    val year: String? = null,
    val extension: String? = null,
    val addedAt: Long = 0,
)

@Serializable
data class BackupProgress(
    val kind: String,
    val itemId: Long,
    val seriesId: Long? = null,
    val title: String,
    val poster: String? = null,
    val extension: String,
    val season: Int? = null,
    val episode: Int? = null,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val updatedAt: Long,
)

@Serializable
data class BackupSettings(
    val backgroundPlayback: Boolean = true,
    val limitVideoToDisplay: Boolean = true,
    val textureVideo: Boolean = false,
    val vodSort: String = VodSort.DEFAULT.name,
    val language: String = "",
    val autoStart: Boolean = false,
    val uiScale: Float = 1f,
    val secureDns: Boolean = true,
    val epgPresets: List<String>? = null,
    val animations: Boolean = true,
    val panelTimeout: Int = 5,
    val listFullscreen: Int = 20,
    val compactList: Boolean = false,
    val streamBadges: Boolean = true,
    val clockOverlay: Boolean = false,
    val accent: String = "orange",
    val screenMargin: Int = 0,
)

/** Per-account options that are not secrets. */
@Serializable
data class BackupAccountSettings(
    val streamFormat: String? = null,
    val userAgent: String? = null,
    val epgOffsetHours: Int = 0,
    val epgUrl: String? = null,
)

/** What a restore changed, for the confirmation message. */
data class RestoreResult(val favorites: Int, val favoritesMissing: Int, val watchlist: Int, val progress: Int)

class BackupException(message: String) : Exception(message)

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val playlists: PlaylistRepository,
    private val settings: SettingsStore,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Writes a backup of the active account to [uri] (a document the viewer picked). */
    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val p = playlists.activePlaylistNow() ?: throw BackupException("no account")
        val channels = db.channelDao().observeChannels(p.id).first().associateBy { it.streamId }
        val favorites = db.favoriteDao().observe(p.id).first().map { f ->
            BackupChannel(f.streamId, channels[f.streamId]?.name.orEmpty())
        }
        val file = BackupFile(
            appVersion = BuildConfig.VERSION_NAME,
            exportedAt = System.currentTimeMillis(),
            favorites = favorites,
            hiddenCategories = db.channelDao().observeHidden(p.id).first(),
            lockedCategories = db.channelDao().observeLocked(p.id).first(),
            watchlist = db.watchlistDao().all(p.id).map {
                BackupWatchlist(it.kind, it.itemId, it.title, it.poster, it.rating, it.year, it.extension, it.addedAt)
            },
            progress = db.watchProgressDao().all(p.id).map {
                BackupProgress(
                    it.kind, it.itemId, it.seriesId, it.title, it.poster, it.extension, it.season, it.episode,
                    it.positionMs, it.durationMs, it.finished, it.updatedAt,
                )
            },
            settings = BackupSettings(
                backgroundPlayback = settings.backgroundPlayback.first(),
                limitVideoToDisplay = settings.limitVideoToDisplay.first(),
                textureVideo = settings.textureVideo.first(),
                vodSort = settings.vodSort.first().name,
                language = AppPrefs.language(context),
                autoStart = AppPrefs.autoStart(context),
                uiScale = AppPrefs.uiScale.value,
                secureDns = AppPrefs.secureDns.value,
                epgPresets = AppPrefs.epgPresets.value.sorted(),
                animations = AppPrefs.animations.value,
                panelTimeout = AppPrefs.panelTimeout.value,
                listFullscreen = AppPrefs.listFullscreen.value,
                compactList = AppPrefs.compactList.value,
                streamBadges = AppPrefs.streamBadges.value,
                clockOverlay = AppPrefs.clockOverlay.value,
                accent = AppPrefs.accent.value,
                screenMargin = AppPrefs.screenMargin.value,
            ),
            account = BackupAccountSettings(p.streamFormat, p.userAgent, p.epgOffsetHours, p.epgUrl),
        )
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw BackupException("cannot write")
        out.use { it.write(json.encodeToString(BackupFile.serializer(), file).toByteArray()) }
        favorites.size
    }

    /** Reads and checks a backup file without changing anything. */
    suspend fun read(uri: Uri): BackupFile = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw BackupException("cannot read")
        val file = runCatching { json.decodeFromString(BackupFile.serializer(), text) }
            .getOrElse { throw BackupException("not a backup") }
        if (file.app != BackupFile.APP_ID) throw BackupException("not a backup")
        if (file.format > BackupFile.FORMAT) throw BackupException("newer format")
        file
    }

    /**
     * Applies [file] to the active account: favorites (and their order) and hidden categories are replaced,
     * "My List" and resume points are merged, settings are overwritten. Channels are matched by stream id when
     * the name agrees, otherwise by name, so a backup from another server address still mostly works.
     */
    suspend fun restore(file: BackupFile): RestoreResult = withContext(Dispatchers.IO) {
        val p = playlists.activePlaylistNow() ?: throw BackupException("no account")
        val channels = db.channelDao().observeChannels(p.id).first()
        val byId = channels.associateBy { it.streamId }
        val byName = channels.groupBy { it.name.trim().lowercase() }
        val matched = LinkedHashSet<Long>()
        var missing = 0
        for (f in file.favorites) {
            val sameId = byId[f.streamId]?.takeIf { f.name.isBlank() || it.name.trim().equals(f.name.trim(), ignoreCase = true) }
            val id = sameId?.streamId ?: byName[f.name.trim().lowercase()]?.firstOrNull()?.streamId ?: byId[f.streamId]?.streamId
            if (id == null) missing++ else matched += id
        }
        db.withTransaction {
            db.favoriteDao().deleteAll(p.id)
            db.favoriteDao().upsert(matched.mapIndexed { i, id -> FavoriteEntity(p.id, id, i) })
            db.channelDao().deleteHidden(p.id)
            db.channelDao().hideAll(file.hiddenCategories.map { HiddenCategoryEntity(p.id, it) })
            file.lockedCategories?.let { locked ->
                db.channelDao().deleteLocked(p.id)
                db.channelDao().lockAll(locked.map { LockedCategoryEntity(p.id, it) })
            }
            db.watchlistDao().insertAll(file.watchlist.map {
                WatchlistEntity(p.id, it.kind, it.itemId, it.title, it.poster, it.rating, it.year, it.extension, it.addedAt)
            })
            db.watchProgressDao().upsertAll(file.progress.map {
                WatchProgressEntity(
                    p.id, it.kind, it.itemId, it.seriesId, it.title, it.poster, it.extension, it.season, it.episode,
                    it.positionMs, it.durationMs, it.finished, it.updatedAt,
                )
            })
            db.playlistDao().update(
                p.copy(
                    streamFormat = file.account.streamFormat ?: p.streamFormat,
                    userAgent = file.account.userAgent,
                    epgOffsetHours = file.account.epgOffsetHours,
                    epgUrl = file.account.epgUrl,
                )
            )
        }
        val s = file.settings
        settings.setBackgroundPlayback(s.backgroundPlayback)
        settings.setLimitVideoToDisplay(s.limitVideoToDisplay)
        settings.setTextureVideo(s.textureVideo)
        runCatching { VodSort.valueOf(s.vodSort) }.getOrNull()?.let { settings.setVodSort(it) }
        AppPrefs.setLanguage(context, s.language)
        AppPrefs.setAutoStart(context, s.autoStart)
        AppPrefs.setUiScale(context, s.uiScale)
        AppPrefs.setSecureDns(context, s.secureDns)
        s.epgPresets?.let { AppPrefs.setEpgPresets(context, it.toSet()) }
        AppPrefs.setAnimations(context, s.animations)
        if (s.panelTimeout in AppPrefs.panelTimeouts) AppPrefs.setPanelTimeout(context, s.panelTimeout)
        if (s.listFullscreen in AppPrefs.listFullscreenDelays) AppPrefs.setListFullscreen(context, s.listFullscreen)
        AppPrefs.setCompactList(context, s.compactList)
        AppPrefs.setStreamBadges(context, s.streamBadges)
        AppPrefs.setClockOverlay(context, s.clockOverlay)
        AppPrefs.setAccent(context, s.accent)
        AppPrefs.setScreenMargin(context, s.screenMargin)
        RestoreResult(matched.size, missing, file.watchlist.size, file.progress.size)
    }
}
