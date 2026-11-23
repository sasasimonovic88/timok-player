package rs.timok.player.data.settings

import rs.timok.player.domain.VodSort
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val activePlaylistKey = longPreferencesKey("active_playlist")
    private val legacyStreamKey = longPreferencesKey("last_stream")
    private val legacyGroupKey = stringPreferencesKey("last_group")
    private val backgroundPlaybackKey = booleanPreferencesKey("background_playback")
    private val limitVideoKey = booleanPreferencesKey("limit_video_to_display")
    private val textureVideoKey = booleanPreferencesKey("texture_video")
    private val vodSortKey = stringPreferencesKey("vod_sort")

    val activePlaylistId: Flow<Long?> = context.dataStore.data.map { it[activePlaylistKey] }

    /** Live video behind the channel list; can be turned off on weak boxes. */
    val backgroundPlayback: Flow<Boolean> = context.dataStore.data.map { it[backgroundPlaybackKey] ?: true }

    suspend fun setBackgroundPlayback(enabled: Boolean) = context.dataStore.edit { it[backgroundPlaybackKey] = enabled }

    /** Skip video above the display resolution (min. 1080p); protects boxes whose decoder dies on 4K. */
    val limitVideoToDisplay: Flow<Boolean> = context.dataStore.data.map { it[limitVideoKey] ?: true }

    suspend fun setLimitVideoToDisplay(enabled: Boolean) = context.dataStore.edit { it[limitVideoKey] = enabled }

    /**
     * Draw video through a TextureView instead of a SurfaceView. Some TVs (seen on a TCL 4K) leave
     * trails of moving overlays above a SurfaceView; a TextureView repaints the whole window with
     * every video frame, at the cost of more GPU work.
     */
    val textureVideo: Flow<Boolean> = context.dataStore.data.map { it[textureVideoKey] ?: false }

    /** Order of the movie and series grids. */
    val vodSort: Flow<VodSort> = context.dataStore.data.map { p ->
        p[vodSortKey]?.let { runCatching { VodSort.valueOf(it) }.getOrNull() } ?: VodSort.DEFAULT
    }

    suspend fun setVodSort(sort: VodSort) = context.dataStore.edit { it[vodSortKey] = sort.name }

    suspend fun setTextureVideo(enabled: Boolean) = context.dataStore.edit { it[textureVideoKey] = enabled }

    suspend fun setActivePlaylist(id: Long?) = context.dataStore.edit {
        if (id == null) it.remove(activePlaylistKey) else it[activePlaylistKey] = id
    }

    private fun lastStreamKey(playlistId: Long) = longPreferencesKey("last_stream_$playlistId")
    private fun lastGroupKey(playlistId: Long) = stringPreferencesKey("last_group_$playlistId")

    /**
     * Last channel and group of an account. Versions before 0.5.6 had one account and stored them without
     * an id; the first read moves those to the account that is read (the only one at that time).
     */
    suspend fun lastWatched(playlistId: Long): Pair<Long?, String?> {
        var result: Pair<Long?, String?> = null to null
        context.dataStore.edit { p ->
            val oldStream = p[legacyStreamKey]
            val oldGroup = p[legacyGroupKey]
            if (oldStream != null || oldGroup != null) {
                if (p[lastStreamKey(playlistId)] == null) oldStream?.let { p[lastStreamKey(playlistId)] = it }
                if (p[lastGroupKey(playlistId)] == null) oldGroup?.let { p[lastGroupKey(playlistId)] = it }
                p.remove(legacyStreamKey)
                p.remove(legacyGroupKey)
            }
            result = p[lastStreamKey(playlistId)] to p[lastGroupKey(playlistId)]
        }
        return result
    }

    suspend fun setLastWatched(playlistId: Long, streamId: Long, group: String) = context.dataStore.edit {
        it[lastStreamKey(playlistId)] = streamId
        it[lastGroupKey(playlistId)] = group
    }

    suspend fun setLastGroup(playlistId: Long, group: String) = context.dataStore.edit { it[lastGroupKey(playlistId)] = group }

    /** Forgets everything stored per account (account removed). */
    suspend fun clearAccount(playlistId: Long) = context.dataStore.edit {
        it.remove(lastStreamKey(playlistId))
        it.remove(lastGroupKey(playlistId))
        it.remove(recentKey(playlistId))
    }

    private fun recentKey(playlistId: Long) = stringPreferencesKey("recent_channels_$playlistId")

    /** Channels last opened on this account, newest first (at most [RECENT_MAX]). */
    fun recentChannels(playlistId: Long): Flow<List<Long>> = context.dataStore.data.map { p ->
        p[recentKey(playlistId)].orEmpty().split(',').mapNotNull { it.toLongOrNull() }
    }

    suspend fun addRecentChannel(playlistId: Long, streamId: Long) = context.dataStore.edit { p ->
        val key = recentKey(playlistId)
        val list = p[key].orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        p[key] = (listOf(streamId) + list.filter { it != streamId }).take(RECENT_MAX).joinToString(",")
    }

    private companion object {
        const val RECENT_MAX = 10
    }
}
