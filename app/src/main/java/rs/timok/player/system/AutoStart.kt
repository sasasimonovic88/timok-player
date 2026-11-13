package rs.timok.player.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Background activity starts after boot need the overlay grant on Android 10 and newer. */
object AutoStart {

    fun needsOverlayGrant(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(context)

    /** Opens the system screen for the grant; returns false when the device has none (common on TV). */
    fun requestOverlayGrant(context: Context): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }
}
