package rs.timok.player.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import rs.timok.player.R

/**
 * Hands a stream to another installed player (VLC, MX Player, Kodi...). The system chooser lets the
 * viewer pick one each time. The URL carries the provider credentials, as in any IPTV player; it goes
 * only to the app the viewer picks.
 */
object ExternalPlayer {

    /** True when at least one installed app can play a network video. */
    fun available(context: Context): Boolean =
        context.packageManager.queryIntentActivities(viewIntent(Uri.parse("http://localhost/v.mp4")), 0).isNotEmpty()

    /** Returns false when no app can open the stream. */
    fun open(context: Context, url: String, title: String, userAgent: String, startMs: Long = 0): Boolean {
        val intent = viewIntent(Uri.parse(url))
            .putExtra("title", title)
            // MX Player reads request headers from a flat key/value array.
            .putExtra("headers", arrayOf("User-Agent", userAgent))
        // VLC resumes from "position" (milliseconds).
        if (startMs > 0) intent.putExtra("position", startMs)
        val chooser = Intent.createChooser(intent, context.getString(R.string.external_player_choose))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun viewIntent(uri: Uri) = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
}
