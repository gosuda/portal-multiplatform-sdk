package org.gosuda.portal.sample

import android.content.Context
import org.gosuda.portal.Capability
import org.gosuda.portal.PortalClient
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalTunnel

/**
 * Compile-only contract for the advanced low-level path.
 *
 * The sample's primary flow uses the intent factories and `publish`; this
 * fixture keeps the raw `PortalConfig` constructor, `open` (accepted-before-
 * ready), `awaitReady`, and the authoritative `state` stream compiled so the
 * escape hatch cannot drift from the exported API.
 */
internal object AdvancedContract {

    /**
     * Opens a tunnel without waiting for readiness: the caller observes
     * CONNECTING through `state` and awaits a single capability itself.
     */
    suspend fun openAndObserve(context: Context): Pair<PortalClient, PortalTunnel> {
        val client = PortalClient(context.applicationContext)
        val tunnel = client.open(
            PortalConfig(
                name = "advanced-site",
                staticDir = "/data/site",
                staticIndex = "index.html",
                discovery = true
            )
        )
        tunnel.state.collect { snapshot ->
            // Render CONNECTING/ACTIVE transitions and relay updates live.
            println("${snapshot.phase} ${snapshot.primaryPublicUrl}")
        }
        tunnel.awaitReady(Capability.STATIC_SITE, timeoutMillis = 15_000)
        return client to tunnel
    }
}
