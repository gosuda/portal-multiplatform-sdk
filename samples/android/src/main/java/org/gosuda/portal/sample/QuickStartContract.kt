package org.gosuda.portal.sample

import android.content.Context
import org.gosuda.portal.PortalClient
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalTunnel

/**
 * Compile-only contract for the documented Android quick start.
 *
 * This file is never invoked by the sample UI; it exists so a public API
 * change that breaks the documented easy path fails compilation instead of
 * silently drifting from the README. The publish body below is the
 * ≤10-app-code-line budget for exposing a loopback HTTP server.
 */
internal object QuickStartContract {

    /**
     * Publishes an HTTP server already listening on 127.0.0.1:8080.
     * Returns the client (which must outlive the tunnel) and the handle.
     */
    suspend fun publishLocalHttpServer(context: Context): Pair<PortalClient, PortalTunnel> {
        val client = PortalClient(context.applicationContext)
        val tunnel = client.publish(
            PortalConfig.http("127.0.0.1:8080", name = "device-api")
        )
        checkNotNull(tunnel.state.value.primaryPublicUrl)
        return client to tunnel
    }
}
