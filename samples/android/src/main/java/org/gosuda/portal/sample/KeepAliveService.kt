package org.gosuda.portal.sample

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Minimal foreground service that keeps the process alive while a tunnel is
 * running. The tunnel itself is owned by [PortalClientHolder]; this service
 * only prevents Android from killing the process in the background.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val channelId = "portal_keepalive"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Portal tunnel", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Portal tunnel active")
            .setContentText("Serving content from this device")
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
