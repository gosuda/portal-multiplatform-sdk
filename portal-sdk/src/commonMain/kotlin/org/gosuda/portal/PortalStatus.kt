package org.gosuda.portal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-relay snapshot as reported by the native runtime.
 */
@Serializable
public data class PortalRelayStatus(
    @SerialName("relay_url") val relayUrl: String,
    @SerialName("public_url") val publicUrl: String? = null,
    @SerialName("udp_addr") val udpAddr: String? = null,
    @SerialName("tcp_addr") val tcpAddr: String? = null,
    @SerialName("version") val version: String? = null,
    @SerialName("state") val state: String,
    @SerialName("failure") val failure: String? = null,
    @SerialName("error") val error: String? = null
) {
    val isReady: Boolean get() = state.equals("ready", ignoreCase = true)
    val isConnecting: Boolean get() = state.equals("connecting", ignoreCase = true)
    val isFailed: Boolean get() = state.equals("failed", ignoreCase = true)

    /** True when the relay failed the MITM self-probe (upstream `RelayFailureMITM`). */
    val isMitm: Boolean get() = failure.equals("mitm", ignoreCase = true)

    /** Mirrors upstream `RelayStatus.Active`: usable registered listener. */
    val isActive: Boolean
        get() = !isFailed && (isReady || publicUrl != null || udpAddr != null || tcpAddr != null)
}

/**
 * Authoritative tunnel status snapshot decoded from the native runtime.
 * Wire-compatible with the Android/iOS/Flutter Portal SDKs.
 */
@Serializable
public data class PortalStatus(
    @SerialName("tunnel_id") val tunnelId: String,
    @SerialName("name") val name: String,
    @SerialName("address") val address: String,
    @SerialName("active") val active: Boolean,
    @SerialName("public_urls") val publicUrls: List<String> = emptyList(),
    @SerialName("relays") val relays: List<PortalRelayStatus> = emptyList()
) {
    val primaryPublicUrl: String? get() = publicUrls.firstOrNull()
}
