package rs.timok.player.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val server: String,
    val username: String,
    val password: String,
    /** Optional per-playlist User-Agent override (Phase 2); null means the app default. */
    val userAgent: String? = null,
    val streamFormat: String = "HLS",
    /** Hours added to XMLTV times for panels that send wrong zone info. */
    val epgOffsetHours: Int = 0,
    val status: String = "",
    val expiresAt: Long? = null,
    val maxConnections: Int = 1,
    val channelsUpdatedAt: Long? = null,
    val epgUpdatedAt: Long? = null,
    /** Optional XMLTV URL used instead of the server's guide (some panels serve none). */
    val epgUrl: String? = null,
    /** Why the last guide download failed; null after a successful one. */
    val epgError: String? = null,
)

@Entity(tableName = "categories", primaryKeys = ["playlistId", "categoryId"])
data class CategoryEntity(
    val playlistId: Long,
    val categoryId: String,
    val name: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "channels",
    primaryKeys = ["playlistId", "streamId"],
    indices = [Index("playlistId", "categoryId"), Index("playlistId", "number")],
)
data class ChannelEntity(
    val playlistId: Long,
    val streamId: Long,
    val number: Int,
    val name: String,
    val logo: String?,
    val categoryId: String?,
    /** Lower-cased XMLTV channel id, used to match programs. */
    val epgChannelId: String?,
    val hasArchive: Boolean,
    val sortOrder: Int,
)

/** Live categories the user chose not to see; kept apart from [CategoryEntity] so a refresh keeps them. */
@Entity(tableName = "hidden_categories", primaryKeys = ["playlistId", "categoryId"])
data class HiddenCategoryEntity(
    val playlistId: Long,
    val categoryId: String,
)

@Entity(tableName = "favorites", primaryKeys = ["playlistId", "streamId"])
data class FavoriteEntity(
    val playlistId: Long,
    val streamId: Long,
    val position: Int,
)

@Entity(
    tableName = "programs",
    indices = [Index("playlistId", "channelKey", "startMs")],
)
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    /** Lower-cased XMLTV channel id. */
    val channelKey: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String?,
)

/** Lightweight programme projection for the now/next snapshot. */
data class ProgramBrief(val channelKey: String, val title: String, val startMs: Long, val endMs: Long)

/** A channel with its current and next program, as shown in lists and the info bar. */
data class ChannelRow(
    val streamId: Long,
    val number: Int,
    val name: String,
    val logo: String?,
    val epgChannelId: String?,
    val hasArchive: Boolean,
    val isFavorite: Boolean,
    val nowTitle: String?,
    val nowStart: Long?,
    val nowEnd: Long?,
    val nextTitle: String?,
)

/** Resume point for a movie or an episode. Finished items stay (to show ✓) but leave "continue watching". */
@Entity(
    tableName = "watch_progress",
    primaryKeys = ["playlistId", "kind", "itemId"],
    indices = [Index("playlistId", "kind", "updatedAt"), Index("playlistId", "seriesId")],
)
data class WatchProgressEntity(
    val playlistId: Long,
    /** "MOVIE" or "EPISODE". */
    val kind: String,
    val itemId: Long,
    val seriesId: Long?,
    val title: String,
    val poster: String?,
    val extension: String,
    val season: Int?,
    val episode: Int?,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val updatedAt: Long,
)

/** Name and guide id of a channel, for matching external guides by name. */
data class ChannelEpgName(val name: String, val epgChannelId: String?)

/** A movie or series saved to "My List" to watch later; kept until the user removes it. */
@Entity(
    tableName = "watchlist",
    primaryKeys = ["playlistId", "kind", "itemId"],
    indices = [Index("playlistId", "kind", "addedAt")],
)
data class WatchlistEntity(
    val playlistId: Long,
    /** "MOVIE" or "SERIES". */
    val kind: String,
    /** Movie stream id or series id. */
    val itemId: Long,
    val title: String,
    val poster: String?,
    val rating: String?,
    val year: String?,
    /** Container extension for movies; null for series. */
    val extension: String?,
    val addedAt: Long,
)
