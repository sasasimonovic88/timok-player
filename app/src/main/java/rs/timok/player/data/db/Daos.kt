package rs.timok.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists ORDER BY id LIMIT 1")
    suspend fun first(): PlaylistEntity?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET epgUpdatedAt = :time, epgError = NULL WHERE id = :id")
    suspend fun setEpgUpdated(id: Long, time: Long)

    @Query("UPDATE playlists SET epgError = :error WHERE id = :id")
    suspend fun setEpgError(id: Long, error: String?)

    @Query("UPDATE playlists SET epgUrl = :url WHERE id = :id")
    suspend fun setEpgUrl(id: Long, url: String?)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM categories WHERE playlistId = :playlistId ORDER BY sortOrder")
    fun observeCategories(playlistId: Long): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY sortOrder")
    fun observeChannels(playlistId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND number = :number ORDER BY sortOrder LIMIT 1")
    suspend fun findByNumber(playlistId: Long, number: Int): ChannelEntity?

    @Query("SELECT DISTINCT epgChannelId FROM channels WHERE playlistId = :playlistId AND epgChannelId IS NOT NULL")
    suspend fun epgChannelIds(playlistId: Long): List<String>

    @Query("SELECT name, epgChannelId FROM channels WHERE playlistId = :playlistId")
    suspend fun epgNames(playlistId: Long): List<ChannelEpgName>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId")
    suspend fun count(playlistId: Long): Int

    @Query("SELECT categoryId FROM hidden_categories WHERE playlistId = :playlistId")
    fun observeHidden(playlistId: Long): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun hide(item: HiddenCategoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun hideAll(items: List<HiddenCategoryEntity>)

    @Query("DELETE FROM hidden_categories WHERE playlistId = :playlistId AND categoryId = :categoryId")
    suspend fun unhide(playlistId: Long, categoryId: String)

    @Query("DELETE FROM hidden_categories WHERE playlistId = :playlistId AND categoryId IN (:categoryIds)")
    suspend fun unhideAll(playlistId: Long, categoryIds: List<String>)

    @Query("DELETE FROM hidden_categories WHERE playlistId = :playlistId")
    suspend fun deleteHidden(playlistId: Long)

    @Query("SELECT categoryId FROM locked_categories WHERE playlistId = :playlistId")
    fun observeLocked(playlistId: Long): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun lockAll(items: List<LockedCategoryEntity>)

    @Query("DELETE FROM locked_categories WHERE playlistId = :playlistId AND categoryId IN (:categoryIds)")
    suspend fun unlockAll(playlistId: Long, categoryIds: List<String>)

    @Query("DELETE FROM locked_categories WHERE playlistId = :playlistId")
    suspend fun deleteLocked(playlistId: Long)

    @Query("SELECT categoryId FROM channels WHERE playlistId = :playlistId AND streamId = :streamId LIMIT 1")
    suspend fun categoryOf(playlistId: Long, streamId: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(items: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(items: List<ChannelEntity>)

    @Query("DELETE FROM categories WHERE playlistId = :playlistId")
    suspend fun deleteCategories(playlistId: Long)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteChannels(playlistId: Long)

    /** Replaces the whole channel list atomically; favorites survive since they are keyed by stream id. */
    @Transaction
    suspend fun replaceAll(playlistId: Long, categories: List<CategoryEntity>, channels: List<ChannelEntity>) {
        deleteCategories(playlistId)
        deleteChannels(playlistId)
        insertCategories(categories)
        channels.chunked(500).forEach { insertChannels(it) }
    }
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE playlistId = :playlistId ORDER BY position")
    fun observe(playlistId: Long): Flow<List<FavoriteEntity>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM favorites WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE playlistId = :playlistId AND streamId = :streamId)")
    suspend fun isFavorite(playlistId: Long, streamId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE playlistId = :playlistId AND streamId = :streamId")
    suspend fun delete(playlistId: Long, streamId: Long)

    @Query("DELETE FROM favorites WHERE playlistId = :playlistId")
    suspend fun deleteAll(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<FavoriteEntity>)

    /** Toggles a favorite; returns true when the channel is now a favorite. */
    @Transaction
    suspend fun toggle(playlistId: Long, streamId: Long): Boolean {
        return if (isFavorite(playlistId, streamId)) {
            delete(playlistId, streamId)
            false
        } else {
            insert(FavoriteEntity(playlistId, streamId, nextPosition(playlistId)))
            true
        }
    }

    /** Persists a new manual order; positions follow the given list. */
    @Transaction
    suspend fun reorder(playlistId: Long, orderedStreamIds: List<Long>) {
        upsert(orderedStreamIds.mapIndexed { i, id -> FavoriteEntity(playlistId, id, i) })
    }
}

@Dao
interface ProgramDao {
    @Query("SELECT channelKey, title, startMs, endMs FROM programs WHERE playlistId = :playlistId AND startMs <= :now AND endMs > :now")
    suspend fun current(playlistId: Long, now: Long): List<ProgramBrief>

    /** First programme after [now] per channel, looking at most until [until]. */
    @Query(
        "SELECT p.channelKey, p.title, p.startMs, p.endMs FROM programs p " +
            "JOIN (SELECT channelKey, MIN(startMs) AS s FROM programs WHERE playlistId = :playlistId AND startMs > :now AND startMs < :until GROUP BY channelKey) n " +
            "ON p.channelKey = n.channelKey AND p.startMs = n.s WHERE p.playlistId = :playlistId"
    )
    suspend fun next(playlistId: Long, now: Long, until: Long): List<ProgramBrief>

    @Query("SELECT * FROM programs WHERE playlistId = :playlistId AND channelKey = :channelKey AND endMs > :now ORDER BY startMs LIMIT 2")
    fun observeNowNext(playlistId: Long, channelKey: String, now: Long): Flow<List<ProgramEntity>>

    /** Programmes of several channels between [from] and [until], for the guide grid. */
    @Query(
        "SELECT * FROM programs WHERE playlistId = :playlistId AND channelKey IN (:channelKeys) " +
            "AND endMs > :from AND startMs < :until ORDER BY channelKey, startMs"
    )
    suspend fun forChannels(playlistId: Long, channelKeys: List<String>, from: Long, until: Long): List<ProgramEntity>

    /** Whole stored schedule of one channel from [from] on (the guide keeps a few days ahead). */
    @Query("SELECT * FROM programs WHERE playlistId = :playlistId AND channelKey = :channelKey AND endMs > :from ORDER BY startMs")
    fun observeSchedule(playlistId: Long, channelKey: String, from: Long): Flow<List<ProgramEntity>>

    @Insert
    suspend fun insert(items: List<ProgramEntity>)

    @Query("DELETE FROM programs WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("DELETE FROM programs WHERE playlistId = :playlistId AND channelKey = :channelKey")
    suspend fun deleteForChannel(playlistId: Long, channelKey: String)

    @Query("UPDATE programs SET playlistId = :to WHERE playlistId = :from")
    suspend fun movePlaylist(from: Long, to: Long)

    /** Swaps freshly parsed rows (stored under a temporary id) in place of the old guide. */
    @Transaction
    suspend fun swapIn(playlistId: Long, tempId: Long) {
        deleteForPlaylist(playlistId)
        movePlaylist(tempId, playlistId)
    }

    @Query("DELETE FROM programs WHERE endMs < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress WHERE playlistId = :playlistId AND kind = :kind AND itemId = :itemId")
    fun observe(playlistId: Long, kind: String, itemId: Long): Flow<WatchProgressEntity?>

    @Query("SELECT * FROM watch_progress WHERE playlistId = :playlistId AND kind = :kind AND itemId = :itemId")
    suspend fun get(playlistId: Long, kind: String, itemId: Long): WatchProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WatchProgressEntity)

    @Query("SELECT * FROM watch_progress WHERE playlistId = :playlistId AND kind = 'MOVIE' AND finished = 0 ORDER BY updatedAt DESC LIMIT 40")
    fun observeMovies(playlistId: Long): Flow<List<WatchProgressEntity>>

    /** Latest watched episode per series. */
    @Query(
        "SELECT * FROM watch_progress w WHERE playlistId = :playlistId AND kind = 'EPISODE' AND updatedAt = " +
            "(SELECT MAX(updatedAt) FROM watch_progress x WHERE x.playlistId = w.playlistId AND x.kind = 'EPISODE' AND x.seriesId = w.seriesId) " +
            "ORDER BY updatedAt DESC LIMIT 40"
    )
    fun observeSeries(playlistId: Long): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE playlistId = :playlistId AND kind = 'EPISODE' AND seriesId = :seriesId")
    fun observeEpisodes(playlistId: Long, seriesId: Long): Flow<List<WatchProgressEntity>>

    @Query("DELETE FROM watch_progress WHERE playlistId = :playlistId")
    suspend fun deleteAll(playlistId: Long)

    @Query("SELECT * FROM watch_progress WHERE playlistId = :playlistId")
    suspend fun all(playlistId: Long): List<WatchProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<WatchProgressEntity>)
}

@Dao
interface WatchlistDao {
    /** Newest first, like the order the user added them in. */
    @Query("SELECT * FROM watchlist WHERE playlistId = :playlistId AND kind = :kind ORDER BY addedAt DESC")
    fun observe(playlistId: Long, kind: String): Flow<List<WatchlistEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE playlistId = :playlistId AND kind = :kind AND itemId = :itemId)")
    fun observeContains(playlistId: Long, kind: String, itemId: Long): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE playlistId = :playlistId AND kind = :kind AND itemId = :itemId)")
    suspend fun contains(playlistId: Long, kind: String, itemId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE playlistId = :playlistId AND kind = :kind AND itemId = :itemId")
    suspend fun delete(playlistId: Long, kind: String, itemId: Long)

    @Query("DELETE FROM watchlist WHERE playlistId = :playlistId")
    suspend fun deleteAll(playlistId: Long)

    @Query("SELECT * FROM watchlist WHERE playlistId = :playlistId ORDER BY addedAt")
    suspend fun all(playlistId: Long): List<WatchlistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<WatchlistEntity>)

    /** Adds or removes [item]; returns true when it is now on the list. */
    @Transaction
    suspend fun toggle(item: WatchlistEntity): Boolean {
        return if (contains(item.playlistId, item.kind, item.itemId)) {
            delete(item.playlistId, item.kind, item.itemId)
            false
        } else {
            insert(item)
            true
        }
    }
}
