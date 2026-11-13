package rs.timok.player.ui.update

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import rs.timok.player.data.update.UpdateManager
import javax.inject.Inject

/** Gives screens access to the app-wide [UpdateManager]. */
@HiltViewModel
class UpdateViewModel @Inject constructor(val updates: UpdateManager) : ViewModel()
