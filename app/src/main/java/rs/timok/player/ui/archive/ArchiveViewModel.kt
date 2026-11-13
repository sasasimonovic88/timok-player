package rs.timok.player.ui.archive

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import rs.timok.player.data.repo.PlaylistRepository
import rs.timok.player.data.repo.VodPlayRequest
import rs.timok.player.domain.ArchiveProgram
import rs.timok.player.domain.VodKind
import rs.timok.player.ui.components.userMessage
import javax.inject.Inject

sealed interface ArchiveState {
    data object Loading : ArchiveState
    data class Error(val message: String) : ArchiveState
    data class Ready(val programs: List<ArchiveProgram>) : ArchiveState
}

@HiltViewModel
class ArchiveViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
    private val playlists: PlaylistRepository,
) : ViewModel() {

    private val streamId: Long = savedState.get<Long>("streamId")!!
    val channelName: String = savedState.get<String>("name").orEmpty()
    private val logo: String? = savedState.get<String>("logo")?.takeIf { it.isNotEmpty() }

    private val _state = MutableStateFlow<ArchiveState>(ArchiveState.Loading)
    val state: StateFlow<ArchiveState> = _state

    init {
        load()
    }

    fun load() {
        _state.value = ArchiveState.Loading
        viewModelScope.launch {
            _state.value = try {
                ArchiveState.Ready(playlists.archive(streamId))
            } catch (e: Exception) {
                ArchiveState.Error(e.userMessage(context))
            }
        }
    }

    /** Catch-up plays in the VOD player: pause and seek work, nothing is remembered. */
    suspend fun playRequest(program: ArchiveProgram): VodPlayRequest? {
        val url = playlists.catchupUrl(streamId, program) ?: return null
        return VodPlayRequest(
            kind = VodKind.MOVIE,
            itemId = streamId,
            seriesId = null,
            title = channelName,
            poster = logo,
            extension = "ts",
            season = null,
            episode = null,
            episodeTitle = program.title,
            url = url,
        )
    }
}
