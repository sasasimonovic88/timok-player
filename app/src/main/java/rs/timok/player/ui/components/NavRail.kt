package rs.timok.player.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import rs.timok.player.R

enum class NavDest { SEARCH, TV, MOVIES, SERIES, SETTINGS }

/** Left menu shared by the top-level screens: icons only, expands with labels while it has focus. */
@Composable
fun NavRail(
    selected: NavDest,
    onNavigate: (NavDest) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) 220.dp else 72.dp, tween(150), label = "rail")
    val focusManager = LocalFocusManager.current

    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .padding(start = 12.dp)
            .onFocusChanged { expanded = it.hasFocus },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = null,
            modifier = Modifier.size(48.dp).padding(bottom = 4.dp),
        )
        Spacer(Modifier.height(40.dp))
        val items = listOf(
            Triple(NavDest.SEARCH, R.drawable.ic_nav_search, R.string.nav_search),
            Triple(NavDest.TV, R.drawable.ic_nav_tv, R.string.nav_tv),
            Triple(NavDest.MOVIES, R.drawable.ic_nav_movies, R.string.nav_movies),
            Triple(NavDest.SERIES, R.drawable.ic_nav_series, R.string.nav_series),
            Triple(NavDest.SETTINGS, R.drawable.ic_nav_settings, R.string.settings),
        )
        for ((dest, icon, label) in items) {
            RailItem(icon, stringResource(label), expanded, selected = dest == selected) {
                if (!enabled) return@RailItem
                // The current screen's item just moves focus into its content.
                if (dest == selected) focusManager.moveFocus(FocusDirection.Right) else onNavigate(dest)
            }
        }
    }
}

@Composable
private fun RailItem(icon: Int, label: String, expanded: Boolean, selected: Boolean, onClick: () -> Unit) {
    TvClickable(onClick = onClick, selected = selected, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(24.dp))
            if (expanded) {
                Text(label, fontSize = 17.sp, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}
