package rs.timok.player.ui.settings

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import rs.timok.player.data.net.SecureDns
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rs.timok.player.R
import rs.timok.player.data.db.PlaylistEntity
import rs.timok.player.data.repo.ChannelRepository
import rs.timok.player.data.repo.PlaylistRepository
import rs.timok.player.data.repo.VodRepository
import rs.timok.player.data.source.XtreamSource
import rs.timok.player.domain.VodKind
import rs.timok.player.data.settings.AppPrefs
import rs.timok.player.data.settings.SettingsStore
import rs.timok.player.player.PlaybackController
import rs.timok.player.system.AutoStart
import rs.timok.player.system.LocaleHelper
import rs.timok.player.ui.components.userMessage
import javax.inject.Inject

/** A category and whether the user hid it; [key] is what the hidden-categories table stores. */
data class CategoryToggle(val key: String, val name: String, val hidden: Boolean)

/** Which list the category manager is showing. */
enum class CategoryTab { LIVE, MOVIES, SERIES }

/** Categories of the current tab; VOD ones come from the server, so they can be loading or fail. */
sealed interface CategoriesState {
    data object Loading : CategoriesState
    data class Error(val message: String) : CategoriesState
    data class Ready(val items: List<CategoryToggle>) : CategoriesState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlists: PlaylistRepository,
    private val channels: ChannelRepository,
    private val vod: VodRepository,
    private val settings: SettingsStore,
    private val playback: PlaybackController,
    private val okHttp: OkHttpClient,
    private val dns: SecureDns,
) : ViewModel() {

    val playlist: StateFlow<PlaylistEntity?> =
        playlists.activePlaylist.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val backgroundPlayback: StateFlow<Boolean> =
        settings.backgroundPlayback.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val limitVideo: StateFlow<Boolean> =
        settings.limitVideoToDisplay.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val playlistId = playlist.map { it?.id }.distinctUntilChanged()

    /** Live categories, for the tile summary. */
    val liveCategories: StateFlow<List<CategoryToggle>> = playlistId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else liveToggles(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _categoryTab = MutableStateFlow(CategoryTab.LIVE)
    val categoryTab: StateFlow<CategoryTab> = _categoryTab

    fun selectCategoryTab(tab: CategoryTab) {
        _categoryTab.value = tab
    }

    val categories: StateFlow<CategoriesState> = combine(playlistId, _categoryTab) { id, tab -> id to tab }
        .flatMapLatest { (id, tab) ->
            when {
                id == null -> flowOf(CategoriesState.Ready(emptyList()))
                tab == CategoryTab.LIVE -> liveToggles(id).map { CategoriesState.Ready(it) }
                else -> vodToggles(id, if (tab == CategoryTab.MOVIES) VodKind.MOVIE else VodKind.SERIES)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CategoriesState.Loading)

    private fun liveToggles(id: Long) = channels.allCategories(id).map { list ->
        list.map { (c, hidden) -> CategoryToggle(c.categoryId, c.name, hidden) }
    }

    private fun vodToggles(id: Long, kind: VodKind): Flow<CategoriesState> = flow {
        emit(CategoriesState.Loading)
        val cats = try {
            vod.categories(kind)
        } catch (e: Exception) {
            emit(CategoriesState.Error(e.userMessage(context)))
            return@flow
        }
        emitAll(channels.hiddenKeys(id).map { hidden ->
            CategoriesState.Ready(cats.map {
                val key = ChannelRepository.vodKey(kind, it.id)
                CategoryToggle(key, it.name, key in hidden)
            })
        })
    }

    fun toggleCategory(item: CategoryToggle) {
        val pid = playlist.value?.id ?: return
        viewModelScope.launch { channels.setCategoryHidden(pid, item.key, !item.hidden) }
    }

    /** Applies to the tab currently shown only. */
    fun setAllCategoriesHidden(hidden: Boolean) {
        val pid = playlist.value?.id ?: return
        val keys = (categories.value as? CategoriesState.Ready)?.items?.map { it.key } ?: return
        viewModelScope.launch { channels.setAllCategoriesHidden(pid, keys, hidden) }
    }

    val uiScale: StateFlow<Float> = AppPrefs.uiScale

    fun setUiScale(scale: Float) = AppPrefs.setUiScale(context, scale)

    val animations: StateFlow<Boolean> = AppPrefs.animations
    fun toggleAnimations() = AppPrefs.setAnimations(context, !AppPrefs.animations.value)

    val panelTimeout: StateFlow<Int> = AppPrefs.panelTimeout
    fun setPanelTimeout(seconds: Int) = AppPrefs.setPanelTimeout(context, seconds)

    val compactList: StateFlow<Boolean> = AppPrefs.compactList
    fun toggleCompactList() = AppPrefs.setCompactList(context, !AppPrefs.compactList.value)

    val streamBadges: StateFlow<Boolean> = AppPrefs.streamBadges
    fun toggleStreamBadges() = AppPrefs.setStreamBadges(context, !AppPrefs.streamBadges.value)

    val clockOverlay: StateFlow<Boolean> = AppPrefs.clockOverlay
    fun toggleClockOverlay() = AppPrefs.setClockOverlay(context, !AppPrefs.clockOverlay.value)

    val screenMargin: StateFlow<Int> = AppPrefs.screenMargin
    fun setScreenMargin(percent: Int) = AppPrefs.setScreenMargin(context, percent)

    val accent: StateFlow<String> = AppPrefs.accent
    fun setAccent(name: String) = AppPrefs.setAccent(context, name)

    val listFullscreen: StateFlow<Int> = AppPrefs.listFullscreen
    fun setListFullscreen(seconds: Int) = AppPrefs.setListFullscreen(context, seconds)

    private val _autoStart = MutableStateFlow(AppPrefs.autoStart(context))
    val autoStart: StateFlow<Boolean> = _autoStart

    /** Autostart is on but Android would block it: the overlay grant is missing. */
    private val _autoStartBlocked = MutableStateFlow(false)
    val autoStartBlocked: StateFlow<Boolean> = _autoStartBlocked

    /** The device has no screen to grant the overlay permission. */
    private val _noGrantScreen = MutableStateFlow(false)
    val noGrantScreen: StateFlow<Boolean> = _noGrantScreen

    fun toggleAutoStart() {
        val enable = !_autoStart.value
        AppPrefs.setAutoStart(context, enable)
        _autoStart.value = enable
        refreshAutoStartGrant()
        if (enable && _autoStartBlocked.value) requestAutoStartGrant()
    }

    fun requestAutoStartGrant() {
        _noGrantScreen.value = !AutoStart.requestOverlayGrant(context)
    }

    /** Re-reads settings another screen may have changed (e.g. a restored backup) when the tiles show again. */
    fun reloadPrefs() {
        _autoStart.value = AppPrefs.autoStart(context)
        _language.value = AppPrefs.language(context)
        refreshAutoStartGrant()
    }

    /** Re-checked when the screen resumes, e.g. after returning from the system grant screen. */
    fun refreshAutoStartGrant() {
        _autoStartBlocked.value = _autoStart.value && AutoStart.needsOverlayGrant(context)
    }

    private val _language = MutableStateFlow(AppPrefs.language(context))
    val language: StateFlow<String> = _language

    /** Saves the language and updates the application resources; the activity then recreates itself. */
    fun setLanguage(tag: String) {
        if (tag == _language.value) return
        AppPrefs.setLanguage(context, tag)
        LocaleHelper.apply(context)
        _language.value = tag
    }

    val secureDns: StateFlow<Boolean> = AppPrefs.secureDns

    fun toggleSecureDns() {
        AppPrefs.setSecureDns(context, !secureDns.value)
        // New lookups and connections only; open connections were made with the old answers.
        dns.clear()
        okHttp.connectionPool.evictAll()
    }

    val standbyAwake: StateFlow<Boolean> = AppPrefs.standbyAwake

    fun toggleStandbyAwake() = AppPrefs.setStandbyAwake(context, !standbyAwake.value)

    val decoderMode: StateFlow<rs.timok.player.player.DecoderMode> = AppPrefs.decoderMode
    val bufferMode: StateFlow<rs.timok.player.player.BufferMode> = AppPrefs.bufferMode

    /** Applied at once: the channel behind the settings reopens with the new choice. */
    fun setDecoderMode(mode: rs.timok.player.player.DecoderMode) {
        if (mode == decoderMode.value) return
        AppPrefs.setDecoderMode(context, mode)
        playback.restartLive()
    }

    fun setBufferMode(mode: rs.timok.player.player.BufferMode) {
        if (mode == bufferMode.value) return
        AppPrefs.setBufferMode(context, mode)
        playback.restartLive()
    }

    val textureVideo: StateFlow<Boolean> =
        settings.textureVideo.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleTextureVideo() {
        val enable = !textureVideo.value
        viewModelScope.launch { settings.setTextureVideo(enable) }
    }

    fun toggleLimitVideo() {
        val enable = !limitVideo.value
        viewModelScope.launch { settings.setLimitVideoToDisplay(enable) }
    }

    fun toggleBackgroundPlayback() {
        val enable = !backgroundPlayback.value
        viewModelScope.launch {
            settings.setBackgroundPlayback(enable)
            if (!enable) playback.stop()
        }
    }

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    private val _signedOut = MutableStateFlow(false)
    val signedOut: StateFlow<Boolean> = _signedOut

    private var refreshJob: Job? = null

    /** Channels first, then the guide. Pressing again while it runs does nothing (it would restart the guide). */
    fun refresh() {
        val p = playlist.value ?: return
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _status.value = context.getString(R.string.refreshing)
            try {
                playlists.refreshChannels(p.id)
            } catch (e: Exception) {
                _status.value = e.userMessage(context)
                return@launch
            }
            loadEpg(p.id)
        }
    }

    /** Switches the account to a new server address; returns false when the address does not work. */
    suspend fun changeServer(server: String): Boolean {
        val p = playlist.value ?: return false
        val value = server.trim()
        if (value.isEmpty() || XtreamSource.normalizeServer(value) == p.server) return false
        refreshJob?.cancel()
        _status.value = context.getString(R.string.settings_server_checking)
        return try {
            playlists.changeServer(p.id, value)
            // Drop the stream still pointing at the old address.
            playback.stop()
            _status.value = context.getString(R.string.settings_server_changed)
            refreshJob = viewModelScope.launch { loadEpg(p.id) }
            true
        } catch (e: Exception) {
            _status.value = e.userMessage(context)
            false
        }
    }

    /** Starts a guide download and shows a note until the guide is in or has failed. */
    private suspend fun loadEpg(playlistId: Long) {
        val requestedAt = System.currentTimeMillis()
        // A failure after this point is always a fresh one.
        playlists.clearEpgError(playlistId)
        playlists.refreshEpgNow(playlistId)
        val loading = context.getString(R.string.settings_epg_loading)
        _status.value = loading
        withTimeoutOrNull(EPG_WAIT_MS) {
            playlist.first { (it?.epgUpdatedAt ?: 0) >= requestedAt || it?.epgError != null }
        }
        if (_status.value == loading) _status.value = null
    }

    /** Saves the guide link (empty = server guide) and downloads the guide again. */
    fun setEpgUrl(url: String) {
        val p = playlist.value ?: return
        val value = url.trim().takeIf { it.isNotEmpty() }
        if (value == p.epgUrl) return
        // A new link must restart a download that is still running with the old one.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            playlists.setEpgUrl(p.id, value)
            loadEpg(p.id)
        }
    }

    val epgPresets: StateFlow<Set<String>> = AppPrefs.epgPresets

    /** Stores the built-in guide choice and downloads the guide again if it changed. */
    fun setEpgPresets(ids: Set<String>) {
        if (ids == epgPresets.value) return
        AppPrefs.setEpgPresets(context, ids)
        val p = playlist.value ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { loadEpg(p.id) }
    }

    /** Every stored account, for the account list. */
    val accounts: StateFlow<List<PlaylistEntity>> =
        playlists.allPlaylists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _switchedToOther = MutableStateFlow(false)
    /** The active account was removed and another one took its place. */
    val switchedToOther: StateFlow<Boolean> = _switchedToOther

    fun removeAccount() {
        val p = playlist.value ?: return
        viewModelScope.launch {
            playlists.deletePlaylist(p.id)
            if (playlists.allPlaylists.first().isEmpty()) _signedOut.value = true else _switchedToOther.value = true
        }
    }

    fun renameAccount(name: String) {
        val p = playlist.value ?: return
        viewModelScope.launch { playlists.rename(p.id, name) }
    }

    private companion object {
        const val EPG_WAIT_MS = 10 * 60_000L
    }
}
