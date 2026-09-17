package org.gosuda.portal.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.gosuda.portal.PortalClient
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalTunnel

/**
 * Process-scoped [PortalClient] owner.
 *
 * A tunnel must outlive the screen that started it: Activities are destroyed
 * on rotation, process death, and navigation, but the native session lives in
 * the process. Hold the client here — not in an Activity or ViewModel — and
 * let [PortalTunnelService] keep the process alive when the tunnel must
 * survive backgrounding.
 *
 * Usage:
 * ```
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         PortalClientHolder.init()
 *     }
 * }
 * ```
 */
public object PortalClientHolder {

    @Volatile
    private var clientRef: PortalClient? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The process-wide client. Throws if [init] was not called. */
    public val client: PortalClient
        get() = clientRef
            ?: error("PortalClientHolder.init() not called — call it from Application.onCreate")

    /** True once [init] has run. */
    public val isInitialized: Boolean get() = clientRef != null

    /**
     * Creates the process-wide client. Idempotent; a second call returns the
     * existing client.
     */
    @Synchronized
    public fun init(allowRemoteTargets: Boolean = false): PortalClient =
        clientRef ?: PortalClient(allowRemoteTargets).also { clientRef = it }

    /**
     * Opens a tunnel on the process-wide client. The returned handle is owned
     * by the process, not the caller — closing the caller's scope does not
     * stop it.
     */
    public fun open(config: PortalConfig, onResult: (Result<PortalTunnel>) -> Unit) {
        scope.launch {
            onResult(runCatching { client.open(config) })
        }
    }

    /**
     * Stops all sessions and releases the client. Call from
     * `Application.onTerminate` (emulator/testing only) or an explicit
     * shutdown path; Android does not call `onTerminate` on real devices.
     */
    public fun shutdown() {
        scope.launch {
            clientRef?.close()
            clientRef = null
        }
    }
}
