package rs.timok.player.ui.program

import rs.timok.player.data.db.ChannelRow
import rs.timok.player.data.db.ProgramEntity
import rs.timok.player.data.repo.PlaylistRepository
import rs.timok.player.data.repo.VodPlayRequest
import rs.timok.player.domain.ArchiveProgram
import rs.timok.player.domain.VodKind
import kotlin.math.abs

/**
 * Turns a guide programme that already ran into a catch-up request (channel guide and guide grid). Timeshift URLs
 * need the start in the server's own time zone, so the programme is looked up in the server's archive list by
 * start time instead of converting the guide time. Archive lists are cached per channel for the screen's life.
 */
class CatchupFinder(private val playlists: PlaylistRepository) {
    private val cache = HashMap<Long, List<ArchiveProgram>>()

    /** Null when the channel has no archive or the server's archive does not have this programme. */
    suspend fun request(row: ChannelRow, program: ProgramEntity): VodPlayRequest? {
        if (!row.hasArchive) return null
        val archive = cache[row.streamId]
            ?: runCatching { playlists.archive(row.streamId) }.getOrNull()?.also { cache[row.streamId] = it }
            ?: return null
        val match = archive
            .filter { abs(it.startMs - program.startMs) <= MATCH_WINDOW_MS }
            .minByOrNull { abs(it.startMs - program.startMs) + if (it.title.equals(program.title, true)) 0 else MATCH_WINDOW_MS }
            ?: return null
        val url = playlists.catchupUrl(row.streamId, match) ?: return null
        return VodPlayRequest(
            kind = VodKind.MOVIE,
            itemId = row.streamId,
            seriesId = null,
            title = row.name,
            poster = row.logo,
            extension = "ts",
            season = null,
            episode = null,
            episodeTitle = program.title,
            url = url,
        )
    }

    private companion object {
        /** Guide and archive starts of the same programme rarely differ by more than this. */
        const val MATCH_WINDOW_MS = 10 * 60 * 1000L
    }
}
