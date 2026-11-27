package rs.timok.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import rs.timok.player.player.PlaybackController
import rs.timok.player.player.SleepTimer
import rs.timok.player.system.LocaleHelper
import rs.timok.player.ui.AppNavHost
import rs.timok.player.ui.MainViewModel
import rs.timok.player.ui.player.SleepWarningDialog
import rs.timok.player.ui.theme.TimokTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    @Inject lateinit var playback: PlaybackController
    @Inject lateinit var sleepTimer: SleepTimer
    @Inject lateinit var parental: rs.timok.player.data.parental.ParentalControl

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Also wait for the startup jump to the player so the channel list never flashes.
        splash.setKeepOnScreenCondition { mainViewModel.startRoute.value == null || mainViewModel.startChannel.value != null }
        lifecycleScope.launch {
            // Only act while visible; in the background playback is already paused.
            sleepTimer.expired.collect { if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) goToSleep() }
        }
        // Every screen draws its own background and the video sits below the window. An opaque window background
        // would be painted full screen on every frame only to be covered, which weak TV GPUs feel.
        window.setBackgroundDrawable(null)
        setContent {
            TimokTheme {
                AppNavHost(mainViewModel, playback)
                val remaining by sleepTimer.remainingMs.collectAsStateWithLifecycle()
                val minutes by sleepTimer.minutes.collectAsStateWithLifecycle()
                remaining?.takeIf { it <= SleepTimer.WARNING_MS }?.let { left ->
                    SleepWarningDialog(
                        secondsLeft = ((left + 999) / 1000).toInt(),
                        minutes = minutes,
                        onExtend = sleepTimer::extend,
                        onSleepNow = {
                            sleepTimer.cancel()
                            goToSleep()
                        },
                    )
                }
            }
        }
    }

    /**
     * Apps cannot power the TV off. Instead: close the stream, leave the app and start the screensaver;
     * with nothing keeping the screen on, the TV then sleeps after its own inactivity timeout.
     */
    private fun goToSleep() {
        playback.stopAll()
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator"))
        }.onFailure { Log.w("MainActivity", "Screensaver not available: $it") }
        finishAndRemoveTask()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onStart() {
        super.onStart()
        playback.onAppForeground()
        mainViewModel.onForeground()
    }

    override fun onStop() {
        playback.onAppBackground()
        // Whoever turns the TV on next must enter the PIN again for locked categories.
        parental.cancelRequest()
        parental.relock()
        super.onStop()
    }
}
