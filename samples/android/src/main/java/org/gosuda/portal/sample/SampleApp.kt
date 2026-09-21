package org.gosuda.portal.sample

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.gosuda.portal.PortalTunnel
import org.gosuda.portal.lifecycle.PortalClientHolder
import org.gosuda.portal.sample.content.PublishableContent

/**
 * Application entry point. Initializes the process-wide [PortalClientHolder]
 * so tunnels survive Activity recreation and (with the keep-alive service)
 * backgrounding.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SampleApp : Application() {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val tunnel = MutableStateFlow<PortalTunnel?>(null)
    internal var activeContent: PublishableContent? = null
    internal val busy = MutableStateFlow(false)
    internal val keepAlive = MutableStateFlow(false)
    internal val lastError = MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        PortalClientHolder.init(this)
        keepAlive.value = SampleSettings.getBoolean(this, "keepAlive", false)
        // The tunnel and its local server have the same process lifetime.
        // An Activity observer disappears during rotation or backgrounding.
        scope.launch {
            tunnel.flatMapLatest { it?.state ?: flowOf(null) }
                .collect { snapshot ->
                    if (snapshot?.isTerminal == true) {
                        activeContent?.stop()
                        activeContent = null
                        stopService(Intent(this@SampleApp, KeepAliveService::class.java))
                    }
                }
        }
    }
}
