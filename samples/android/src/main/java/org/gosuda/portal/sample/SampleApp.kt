package org.gosuda.portal.sample

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.gosuda.portal.PortalTunnel
import org.gosuda.portal.lifecycle.PortalClientHolder

/**
 * Application entry point. Initializes the process-wide [PortalClientHolder]
 * so tunnels survive Activity recreation and (with the keep-alive service)
 * backgrounding.
 */
class SampleApp : Application() {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val tunnel = MutableStateFlow<PortalTunnel?>(null)
    internal val busy = MutableStateFlow(false)
    internal val keepAlive = MutableStateFlow(false)
    internal val lastError = MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        PortalClientHolder.init()
    }
}
