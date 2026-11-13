package rs.timok.player.ui.login

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rs.timok.player.BuildConfig
import rs.timok.player.R
import rs.timok.player.data.repo.PlaylistRepository
import rs.timok.player.ui.components.userMessage
import javax.inject.Inject

data class LoginState(
    val server: String = BuildConfig.PRESET_SERVER,
    val username: String = "",
    val password: String = "",
    /** Sideload builds may ship with a preset server; then the field is hidden. */
    val serverLocked: Boolean = BuildConfig.PRESET_SERVER.isNotBlank(),
    val loading: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlists: PlaylistRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state

    fun onServer(v: String) = _state.update { it.copy(server = v.trim(), error = null) }
    fun onUsername(v: String) = _state.update { it.copy(username = v.trim(), error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }

    fun signIn() {
        val s = _state.value
        if (s.loading) return
        if (s.server.isBlank() || s.username.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(error = context.getString(R.string.error_missing_fields)) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                playlists.signIn(s.server, s.username, s.password)
                _state.update { it.copy(loading = false, done = true) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage(context)) }
            }
        }
    }
}
