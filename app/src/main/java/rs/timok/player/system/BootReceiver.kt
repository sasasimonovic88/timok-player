package rs.timok.player.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import rs.timok.player.MainActivity
import rs.timok.player.data.settings.AppPrefs

/** Opens the player after the device boots, when the user turned on autostart. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS || !AppPrefs.autoStart(context)) return
        // Android 10+ allows this background activity start only with the "display over other apps" grant.
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            // Some TV boxes resume from a "quick boot" instead of a cold boot.
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
