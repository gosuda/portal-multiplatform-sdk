package org.gosuda.portal.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.gosuda.portal.PortalTunnel

/**
 * Thread-safe registry of live tunnels owned by one [PortalClient].
 * Exposes the ordered session list as a [StateFlow] for UI observation.
 */
internal class SessionRegistry {
    private val _sessions = MutableStateFlow<List<PortalTunnel>>(emptyList())

    /** Live sessions in open order. */
    val sessions: StateFlow<List<PortalTunnel>> = _sessions.asStateFlow()

    fun register(tunnel: PortalTunnel) {
        _sessions.update { list ->
            if (list.any { it.tunnelId == tunnel.tunnelId }) list else list + tunnel
        }
    }

    fun unregister(tunnelId: String) {
        _sessions.update { list -> list.filterNot { it.tunnelId == tunnelId } }
    }

    fun get(tunnelId: String): PortalTunnel? =
        _sessions.value.firstOrNull { it.tunnelId == tunnelId }

    fun all(): List<PortalTunnel> = _sessions.value

    fun size(): Int = _sessions.value.size
}
