package rs.timok.player.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import rs.timok.player.R
import rs.timok.player.ui.components.TvButton
import rs.timok.player.ui.components.TvTextField
import rs.timok.player.ui.theme.Brand

@Composable
fun LoginScreen(
    onSignedIn: () -> Unit,
    /** Set when adding another account: Back returns, and the screen says it is a new account. */
    onBack: (() -> Unit)? = null,
    vm: LoginViewModel = hiltViewModel(),
) {
    if (onBack != null) androidx.activity.compose.BackHandler(onBack = onBack)
    val state by vm.state.collectAsStateWithLifecycle()
    val firstField = remember { FocusRequester() }
    val buttonFocus = remember { FocusRequester() }

    LaunchedEffect(state.done) { if (state.done) onSignedIn() }
    LaunchedEffect(Unit) { firstField.requestFocus() }

    Box(Modifier.fillMaxSize().background(Brand.Background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
            )
            Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.headlineMedium, color = Brand.TextPrimary)
            Text(stringResource(if (onBack != null) R.string.login_add_subtitle else R.string.login_subtitle), style = MaterialTheme.typography.bodyMedium, color = Brand.TextSecondary)
            Spacer(Modifier.height(4.dp))

            if (!state.serverLocked) {
                TvTextField(
                    value = state.server,
                    onValueChange = vm::onServer,
                    label = stringResource(R.string.login_server),
                    keyboardType = KeyboardType.Uri,
                    modifier = Modifier.focusRequester(firstField),
                )
            }
            TvTextField(
                value = state.username,
                onValueChange = vm::onUsername,
                label = stringResource(R.string.login_username),
                modifier = if (state.serverLocked) Modifier.focusRequester(firstField) else Modifier,
            )
            TvTextField(
                value = state.password,
                onValueChange = vm::onPassword,
                label = stringResource(R.string.login_password),
                password = true,
                imeAction = ImeAction.Done,
                onImeAction = { buttonFocus.requestFocus() },
            )

            state.error?.let {
                Text(it, color = Brand.Error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }

            TvButton(
                text = stringResource(if (state.loading) R.string.login_in_progress else R.string.login_button),
                onClick = vm::signIn,
                enabled = !state.loading,
                modifier = Modifier.focusRequester(buttonFocus).padding(top = 6.dp),
            )
            if (state.loading) {
                Text(stringResource(R.string.loading_channels), color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
