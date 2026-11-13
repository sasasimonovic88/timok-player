package rs.timok.player.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import rs.timok.player.R
import rs.timok.player.data.update.UpdateManager
import rs.timok.player.data.update.UpdateState
import rs.timok.player.ui.components.TvClickable
import rs.timok.player.ui.theme.Brand
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(val updates: UpdateManager) : ViewModel()

/** "Check for updates" on the About page; an available update opens the app-wide update dialog. */
@Composable
fun UpdateRow(modifier: Modifier = Modifier, vm: UpdateViewModel = hiltViewModel()) {
    if (!vm.updates.enabled) return
    val state by vm.updates.state.collectAsStateWithLifecycle()
    val status = when (state) {
        UpdateState.Checking -> stringResource(R.string.update_checking)
        UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
        else -> stringResource(R.string.update_check_hint)
    }
    TvClickable(onClick = { vm.updates.check(userAsked = true) }, modifier = modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.update_check), fontSize = 18.sp, color = Brand.TextPrimary)
            Text(status, fontSize = 14.sp, color = Brand.TextSecondary)
        }
    }
}
