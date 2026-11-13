package rs.timok.player.data.repo

import rs.timok.player.epg.EpgKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import rs.timok.player.data.db.CategoryEntity
import rs.timok.player.data.db.ChannelDao
import rs.timok.player.data.db.ChannelEntity
import rs.timok.player.data.db.ChannelRow
import rs.timok.player.data.db.FavoriteDao
import rs.timok.player.data.db.FavoriteEntity
import rs.timok.player.data.db.HiddenCategoryEntity
import rs.timok.player.data.db.PlaylistDao
import rs.timok.player.data.db.ProgramBrief
import rs.timok.player.data.db.ProgramDao
import rs.timok.player.data.db.ProgramEntity
import rs.timok.player.domain.VodKind
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Identifies a channel group in the UI and in navigation arguments. */
sealed class GroupKey(val key: String) {
    data object Favorites : GroupKey("fav")
    data object All : GroupKey("all")
    /** Channels opened most recently, newest first. */
    data object Recent : GroupKey("recent")
    data class Category(val id: String) : GroupKey("cat:$id")

    companion object {
        fun parse(key: String?): GroupKey = when {
            key == null -> All
            key == "fav" -> Favorites
            key == "recent" -> Recent
            key.startsWith("cat:") -> Category(key.removePrefix("cat:"))
            else -> All
        }
    }
}

/** All channels of a playlist with now/next, pre-split by group so switching groups is a map lookup. */
class ChannelIndex(
    val all: List<ChannelRow>,
    private val byCategory: Map<String, List<ChannelRow>>,
    val favorites: List<ChannelRow>,
) {
    private val byId: Map<Long, ChannelRow> by lazy { (all + favorites).associateBy { it.streamId } }

    /** [GroupKey.Recent] has no fixed rows here; see [recent]. */
    fun rows(group: GroupKey): List<ChannelRow> = when (group) {
        GroupKey.Favorites -> favorites
        GroupKey.All -> all
        GroupKey.Recent -> emptyList()
        is GroupKey.Category -> byCategory[group.id].orEmpty()
    }

    /** Rows of [ids] in that order, skipping channels that no longer exist. */
    fun recent(ids: List<Long>): List<ChannelRow> = ids.mapNotNull { byId[it] }
}

private class EpgSnapshot(val now: Map<String, ProgramBrief>, val next: Map<String, ProgramBrief>)

@Singleton
class ChannelRepository @Inject constructor(
    private val channelDao: ChannelDao,
    private val favoriteDao: FavoriteDao,
    private val programDao: ProgramDao,
    private val playlistDao: PlaylistDao,
    private val settings: rs.timok.player.data.settings.SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val indexes = ConcurrentHashMap<Long, Flow<ChannelIndex>>()

    /** Emits the current time rounded to the minute, so now/next labels roll over. */
    val minuteTicker: Flow<Long> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now - now % 60_000)
            delay(60_000 - now % 60_000)
        }
    }.distinctUntilChanged()

    /** Server categories without the panel's "Account Information" pseudo-category. */
    private fun liveCategories(playlistId: Long): Flow<List<CategoryEntity>> =
        channelDao.observeCategories(playlistId).map { all -> all.filterNot { isAccountInfo(it.name) } }

    /** Categories shown in the channel list (hidden ones left out). */
    fun categories(playlistId: Long): Flow<List<CategoryEntity>> =
        combine(liveCategories(playlistId), hiddenKeys(playlistId)) { all, hidden ->
            all.filter { it.categoryId !in hidden }
        }

    /** Every category with its hidden flag, for the settings screen. */
    fun allCategories(playlistId: Long): Flow<List<Pair<CategoryEntity, Boolean>>> =
        combine(liveCategories(playlistId), hiddenKeys(playlistId)) { all, hidden ->
            all.map { it to (it.categoryId in hidden) }
        }

    /** [key] is a live category id or a [vodKey]. */
    suspend fun setCategoryHidden(playlistId: Long, key: String, hidden: Boolean) =
        if (hidden) channelDao.hide(HiddenCategoryEntity(playlistId, key))
        else channelDao.unhide(playlistId, key)

    suspend fun setAllCategoriesHidden(playlistId: Long, keys: List<String>, hidden: Boolean) =
        if (hidden) channelDao.hideAll(keys.map { HiddenCategoryEntity(playlistId, it) })
        else keys.chunked(500).forEach { channelDao.unhideAll(playlistId, it) }

    /** Hidden live category ids and VOD [vodKey]s; they share one table and never collide. */
    fun hiddenKeys(playlistId: Long): Flow<Set<String>> =
        channelDao.observeHidden(playlistId).map { it.toSet() }.distinctUntilChanged()

    fun channels(playlistId: Long, group: GroupKey): Flow<List<ChannelRow>> =
        if (group == GroupKey.Recent) {
            combine(index(playlistId), settings.recentChannels(playlistId)) { idx, ids -> idx.recent(ids) }.distinctUntilChanged()
        } else {
            index(playlistId).map { it.rows(group) }.distinctUntilChanged()
        }

    /**
     * Shared per playlist and kept warm for a minute after the last screen stops listening,
     * so going to the player and back does not rebuild it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun index(playlistId: Long): Flow<ChannelIndex> = indexes.getOrPut(playlistId) {
        // Re-read the guide every minute and after each import, not on every row the importer writes.
        val epgVersion = playlistDao.observe(playlistId).map { it?.epgUpdatedAt }.distinctUntilChanged()
        val epg = combine(minuteTicker, epgVersion) { now, _ -> now }
            .mapLatest { now -> loadEpg(playlistId, now) }
        // The "Account Information" category is treated as hidden, so its fake channels never show up in "All".
        val accountInfoIds = channelDao.observeCategories(playlistId)
            .map { all -> all.filter { isAccountInfo(it.name) }.map { it.categoryId }.toSet() }
            .distinctUntilChanged()
        val hidden = combine(hiddenKeys(playlistId), accountInfoIds) { user, system -> user + system }
        // Guide keys may need name normalization: once per channel list, not on every minute's guide refresh.
        val channels = channelDao.observeChannels(playlistId)
            .map { list -> list.map { it to EpgKeys.key(it.epgChannelId, it.name) } }
            .flowOn(Dispatchers.Default)
        combine(channels, favoriteDao.observe(playlistId), hidden, epg, ::buildIndex)
            .flowOn(Dispatchers.Default)
            .shareIn(scope, SharingStarted.WhileSubscribed(60_000), replay = 1)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun nowNext(playlistId: Long, epgChannelId: String?): Flow<List<ProgramEntity>> =
        if (epgChannelId == null) flow { emit(emptyList()) }
        else minuteTicker.flatMapLatest { now -> programDao.observeNowNext(playlistId, epgChannelId, now) }

    /** Stored programmes of a channel (by its guide key) that end after [from], oldest first. */
    fun schedule(playlistId: Long, epgKey: String, from: Long): Flow<List<ProgramEntity>> =
        programDao.observeSchedule(playlistId, epgKey, from)

    /**
     * The channel as shown in lists (name, logo, guide key). Looked up in [group] first, so its number matches the
     * list it came from (favorites are numbered by position), then in all channels and favorites.
     */
    suspend fun channelRow(playlistId: Long, streamId: Long, group: GroupKey): ChannelRow? =
        listOf(group, GroupKey.All, GroupKey.Favorites).distinct().firstNotNullOfOrNull { g ->
            channels(playlistId, g).first().firstOrNull { it.streamId == streamId }
        }

    suspend fun findByNumber(playlistId: Long, number: Int): ChannelEntity? = channelDao.findByNumber(playlistId, number)

    suspend fun toggleFavorite(playlistId: Long, streamId: Long): Boolean = favoriteDao.toggle(playlistId, streamId)

    suspend fun reorderFavorites(playlistId: Long, orderedStreamIds: List<Long>) =
        favoriteDao.reorder(playlistId, orderedStreamIds)

    private suspend fun loadEpg(playlistId: Long, now: Long): EpgSnapshot {
        val current = HashMap<String, ProgramBrief>()
        // Overlapping entries happen in sloppy guides; the latest start wins.
        for (p in programDao.current(playlistId, now)) {
            val prev = current[p.channelKey]
            if (prev == null || p.startMs > prev.startMs) current[p.channelKey] = p
        }
        val next = programDao.next(playlistId, now, now + NEXT_LOOKAHEAD_MS).associateBy { it.channelKey }
        return EpgSnapshot(current, next)
    }

    private fun buildIndex(
        channels: List<Pair<ChannelEntity, String>>,
        favorites: List<FavoriteEntity>,
        hidden: Set<String>,
        epg: EpgSnapshot,
    ): ChannelIndex {
        val favoriteIds = favorites.mapTo(HashSet(favorites.size)) { it.streamId }
        val rowsById = HashMap<Long, ChannelRow>(channels.size)
        val ordered = ArrayList<ChannelRow>(channels.size)
        val byCategory = LinkedHashMap<String, MutableList<ChannelRow>>()
        // channels arrive in server order, which is the order inside each category.
        // epgKey: server guide id, or the name key an external guide was matched by.
        for ((c, epgKey) in channels) {
            val now = epg.now[epgKey]
            val row = ChannelRow(
                streamId = c.streamId,
                number = c.number,
                name = c.name,
                logo = c.logo,
                epgChannelId = epgKey,
                hasArchive = c.hasArchive,
                isFavorite = c.streamId in favoriteIds,
                nowTitle = now?.title,
                nowStart = now?.startMs,
                nowEnd = now?.endMs,
                nextTitle = epg.next[epgKey]?.title,
            )
            rowsById[c.streamId] = row
            // Channels of hidden categories stay reachable only through favorites.
            if (c.categoryId != null && c.categoryId in hidden) continue
            ordered += row
            c.categoryId?.let { byCategory.getOrPut(it) { ArrayList() }.add(row) }
        }
        // sortedBy is stable: equal numbers keep server order.
        val all = ordered.sortedBy { it.number }
        return ChannelIndex(
            all = all,
            byCategory = byCategory,
            // Favorites are numbered by their manual order (1, 2, 3...), not by the server number.
            favorites = favorites.mapNotNull { rowsById[it.streamId] }.mapIndexed { i, r -> r.copy(number = i + 1) },
        )
    }

    companion object {
        /** Hidden-category key of a movie or series category (live categories use their plain id). */
        fun vodKey(kind: VodKind, categoryId: String) = "${kind.name}:$categoryId"

        private val ACCOUNT_INFO = Regex("^\\W*account\\s*info", RegexOption.IGNORE_CASE)

        /** Xtream panels list subscription details as fake channels in a category like "Account Information". */
        fun isAccountInfo(categoryName: String) = ACCOUNT_INFO.containsMatchIn(categoryName)

        private const val NEXT_LOOKAHEAD_MS = 12 * 60 * 60 * 1000L
    }
}
