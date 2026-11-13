package rs.timok.player.data.source

import rs.timok.player.domain.AccountInfo
import rs.timok.player.domain.ArchiveProgram
import rs.timok.player.domain.SourceCategory
import rs.timok.player.domain.SourceChannel
import rs.timok.player.domain.StreamFormat
import rs.timok.player.domain.VodDetails
import rs.timok.player.domain.VodItem
import rs.timok.player.domain.VodKind

/**
 * A provider of live channels and VOD (movies, series). Xtream Codes is the primary implementation;
 * an M3U implementation can be added behind the same interface later.
 */
interface PlaylistSource {
    suspend fun authenticate(): AccountInfo
    suspend fun liveCategories(): List<SourceCategory>
    suspend fun liveChannels(): List<SourceChannel>
    fun liveStreamUrl(streamId: Long, format: StreamFormat): String
    /** XMLTV URL for this source, or null when the source has no guide. */
    fun epgUrl(): String?

    suspend fun vodCategories(kind: VodKind): List<SourceCategory>
    /** Items of one category, or of all of them with [ALL_CATEGORIES]. */
    suspend fun vodItems(kind: VodKind, categoryId: String): List<VodItem>
    suspend fun vodDetails(kind: VodKind, id: Long): VodDetails
    fun movieUrl(id: Long, extension: String): String
    fun episodeUrl(id: Long, extension: String): String

    /** Past programmes of a channel that are still in its archive, newest first. */
    suspend fun archive(streamId: Long): List<ArchiveProgram>
    fun catchupUrl(streamId: Long, program: ArchiveProgram): String

    companion object {
        const val ALL_CATEGORIES = "*"
    }
}
