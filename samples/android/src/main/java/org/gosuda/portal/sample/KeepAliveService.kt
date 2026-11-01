package org.gosuda.portal.sample

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Explicit user-requested execution; not a promise of indefinite Android hosting. */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as SampleApp
        if (intent?.action == "stop") {
            if (app.tunnel.value == null && !app.cleanupPending.value) stopSelf() else app.stop()
            // Successful stop releases the service through the process owner.
            // Failed stop keeps the notification and owned listener for retry.
        } else {
            startForegroundWithNotification()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val app = application as SampleApp
        app.lastError.value = "Android's foreground-service time limit was reached. Stopping this owned session; inspect the app and retry if stop fails."
        app.scope.launch {
            app.busy.first { !it }
            app.stop()
        }
        // Android requires the timed-out service to leave foreground promptly.
        // The process owner retains a failed stop and never kills its payload early.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification() {
        val channelId = "portal_keepalive"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channelId, "Portal tunnel", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, KeepAliveService::class.java).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Portal · SDK workbench")
            .setContentText("Publishing from this device. Open for status or retryable stop.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop publishing", stop).build())
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }
}
