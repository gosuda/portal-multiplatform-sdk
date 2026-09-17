package org.gosuda.portal.sample

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import kotlinx.coroutines.launch
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** User-requested foreground execution; Android may still stop the process. */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            val app = application as SampleApp
            if (!app.busy.value) {
                app.busy.value = true
                app.scope.launch {
                    try {
                        app.tunnel.value?.stop()
                        stopSelf()
                    } catch (e: Exception) {
                        app.lastError.value = "Could not stop: ${e.message}"
                    } finally {
                        app.busy.value = false
                    }
                }
            }
        } else {
            startForegroundWithNotification()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val channelId = "portal_keepalive"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Portal tunnel", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, KeepAliveService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Portal · background publishing")
            .setContentText("Tap to view the public address and connection status")
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
