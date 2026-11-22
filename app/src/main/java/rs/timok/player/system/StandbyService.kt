package rs.timok.player.system

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import rs.timok.player.R

/**
 * Runs only while the box is in standby and holds a partial wake lock (see [StandbyWakeLock] for why).
 * A plain wake lock is not enough: once the activity stops the process becomes "cached", and Android 9
 * disables wake locks of cached processes. A foreground service keeps the process, and the lock, active.
 */
class StandbyService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // Held only until the screen turns on again; a timeout would let the suspend attempt through.
    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "timok:standby").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "Standby: wake lock held")
        }
        // Not restarted by the system: after a kill the screen state is unknown.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Log.i(TAG, "Wake lock released")
        super.onDestroy()
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.standby_channel), NotificationManager.IMPORTANCE_MIN),
                )
            }
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        }
        return builder
            .setSmallIcon(R.drawable.ic_set_power)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.standby_notification))
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val TAG = "StandbyWakeLock"
        const val CHANNEL_ID = "standby"
        const val NOTIFICATION_ID = 4101
    }
}
