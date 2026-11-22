package rs.timok.player.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import rs.timok.player.data.settings.AppPrefs

/**
 * Keeps the CPU awake while the box is in standby (screen off), so the kernel never attempts a full suspend.
 *
 * On Amlogic boxes (seen on an S905X3, Android 9) the kernel tries to suspend every ~10 minutes of standby,
 * aborts at once ("Wakeup pending"), and afterwards the hardware video decoder refuses to start
 * ("ammvdec_h264/h265 driver probe failed") until the box is rebooted: after the TV is switched back on,
 * every channel stays black. Any held wake lock blocks that suspend attempt. Screen and HDMI still turn off;
 * on such boxes the suspend never completes anyway, so power use barely changes.
 *
 * The lock itself lives in [StandbyService], started on screen off and stopped on screen on.
 */
object StandbyWakeLock {
    private const val TAG = "StandbyWakeLock"

    /** On by default where the bug was seen; other boxes really suspend and may save power that way. */
    val defaultEnabled: Boolean = Build.HARDWARE.contains("amlogic", ignoreCase = true)

    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> if (AppPrefs.standbyAwake.value) start()
                    Intent.ACTION_SCREEN_ON -> stop()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        // Protected system broadcasts; a dynamically registered receiver gets them while the process lives.
        app.registerReceiver(receiver, filter)
    }

    /** Called when the setting is turned off while the box is already in standby. */
    fun onSettingChanged(enabled: Boolean) {
        if (!enabled && ::app.isInitialized) stop()
    }

    private fun start() {
        val intent = Intent(app, StandbyService::class.java)
        // Android 12+ may refuse a foreground service started from the background; then there is no lock.
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        }.onFailure { Log.w(TAG, "Standby service not started: $it") }
    }

    private fun stop() {
        runCatching { app.stopService(Intent(app, StandbyService::class.java)) }
    }
}
