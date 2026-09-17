package org.gosuda.portal.internal

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.gosuda.portal.PortalTunnel

/**
 * Thread-safe registry of live tunnels owned by one [PortalClient].
 * Exposes the ordered session list as a [StateFlow] for UI observation.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class SessionRegistry {
    private val tunnels = AtomicReference<Map<String, PortalTunnel>>(emptyMap())
    private val _sessions = MutableStateFlow<List<PortalTunnel>>(emptyList())

    /** Live sessions in open order. */
    val sessions: StateFlow<List<PortalTunnel>> = _sessions.asStateFlow()

    fun register(tunnel: PortalTunnel) {
        tunnels.update { it + (tunnel.tunnelId to tunnel) }
        _sessions.value = tunnels.load().values.toList()
    }

    fun unregister(tunnelId: String) {
        tunnels.update { it - tunnelId }
        _sessions.value = tunnels.load().values.toList()
    }

    fun get(tunnelId: String): PortalTunnel? = tunnels.load()[tunnelId]

    fun all(): List<PortalTunnel> = tunnels.load().values.toList()

    fun size(): Int = tunnels.load().size

    private inline fun AtomicReference<Map<String, PortalTunnel>>.update(
        crossinline transform: (Map<String, PortalTunnel>) -> Map<String, PortalTunnel>
    ) {
        while (true) {
            val cur = load()
            if (compareAndSet(cur, transform(cur))) return
        }
    }
}
