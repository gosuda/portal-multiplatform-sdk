package org.gosuda.portal.lifecycle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.gosuda.portal.PortalClient
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalTunnel

/**
 * Foreground-service base that owns a [PortalClient] and its tunnels for the
 * process lifetime.
 *
 * Android kills background processes aggressively; a tunnel that must keep
 * serving while the app is backgrounded needs a foreground service. Subclass
 * this, declare it in the manifest with
 * `android:foregroundServiceType="dataSync"`, and override [buildNotification].
 *
 * The service owns the client: tunnels opened here are not tied to any
 * Activity and survive configuration changes and backgrounding until
 * [stopAllTunnels] or process death.
 */
public abstract class PortalTunnelService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The service-owned client; created lazily on first tunnel. */
    public val client: PortalClient by lazy { PortalClient(applicationContext) }

    private val tunnels = mutableListOf<PortalTunnel>()

    private val binder = LocalBinder()

    public inner class LocalBinder : Binder() {
        public val service: PortalTunnelService get() = this@PortalTunnelService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
    }

    /**
     * Opens a tunnel owned by this service. The tunnel keeps running while
     * the service is alive, independent of any Activity.
     */
    public fun startTunnel(config: PortalConfig, onResult: (Result<PortalTunnel>) -> Unit = {}) {
        serviceScope.launch {
            onResult(runCatching {
                client.open(config).also { tunnels += it }
            })
        }
    }

    /** Stops every tunnel owned by this service. */
    public fun stopAllTunnels() {
        serviceScope.launch {
            tunnels.toList().forEach { runCatching { it.stop() } }
            tunnels.clear()
        }
    }

    override fun onDestroy() {
        serviceScope.launch {
            tunnels.toList().forEach { runCatching { it.stop() } }
            client.close()
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    // ---- notification --------------------------------------------------------

    /** Build the foreground notification shown while the service runs. */
    protected abstract fun buildNotification(): Notification

    protected open val channelId: String get() = "portal_tunnel"
    protected open val channelName: String get() = "Portal tunnel"
    protected open val notificationId: Int get() = 1

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                notificationId,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(notificationId, buildNotification())
        }
    }
}
