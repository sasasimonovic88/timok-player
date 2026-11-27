package rs.timok.player.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        CategoryEntity::class,
        ChannelEntity::class,
        FavoriteEntity::class,
        ProgramEntity::class,
        WatchProgressEntity::class,
        HiddenCategoryEntity::class,
        WatchlistEntity::class,
        LockedCategoryEntity::class,
    ],
    version = 6,
    exportSchema = true,
    // Keep user data (favorites, channels) across schema changes: real migrations only.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun programDao(): ProgramDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun watchlistDao(): WatchlistDao
}
