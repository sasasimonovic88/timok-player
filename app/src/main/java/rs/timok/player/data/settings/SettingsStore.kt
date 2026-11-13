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
    private val lastStreamKey = longPreferencesKey("last_stream")
    private val lastGroupKey = stringPreferencesKey("last_group")
    private val backgroundPlaybackKey = booleanPreferencesKey("background_playback")
    private val limitVideoKey = booleanPreferencesKey("limit_video_to_display")
    private val textureVideoKey = booleanPreferencesKey("texture_video")
    private val vodSortKey = stringPreferencesKey("vod_sort")

    val activePlaylistId: Flow<Long?> = context.dataStore.data.map { it[activePlaylistKey] }
    val lastGroup: Flow<String?> = context.dataStore.data.map { it[lastGroupKey] }

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

    suspend fun lastStreamId(): Long? = context.dataStore.data.first()[lastStreamKey]

    suspend fun setLastWatched(streamId: Long, group: String) = context.dataStore.edit {
        it[lastStreamKey] = streamId
        it[lastGroupKey] = group
    }

    suspend fun setLastGroup(group: String) = context.dataStore.edit { it[lastGroupKey] = group }
}
