package org.gosuda.portal

/**
 * Event emitted by the native tunnel runtime. Event names on the wire are
 * `STARTED`, `STOPPED`, `STATUS_CHANGED`, `MITM_SUSPECTED`, `ERROR`; anything
 * else arrives as [Unknown] so forward-compatible events are not dropped.
 *
 * Events are auxiliary notifications: the authoritative state is
 * [PortalTunnel.state]. Subscribers that attach late do not replay past
 * events; durable information is folded into the snapshot instead.
 */
sealed class PortalEvent {
    abstract val tunnelId: String

    data class Started(override val tunnelId: String, val name: String) : PortalEvent()
    data class Stopped(override val tunnelId: String) : PortalEvent()
    data class StatusChanged(override val tunnelId: String, val status: PortalStatus) : PortalEvent()
    data class MitmSuspected(override val tunnelId: String, val relayUrl: String) : PortalEvent()
    data class Error(override val tunnelId: String, val message: String) : PortalEvent()
    data class Unknown(override val tunnelId: String, val type: String, val rawPayload: String) : PortalEvent()
}
