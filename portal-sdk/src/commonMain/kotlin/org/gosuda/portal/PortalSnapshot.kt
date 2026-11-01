package org.gosuda.portal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Lifecycle phase of a tunnel session.
 *
 * ```
 * IDLE -> STARTING -> CONNECTING -> ACTIVE
 *              \          \          |
 *               +-- fail -> FAILED   |
 *                          \         /
 *                           STOPPING
 *                              |
 *                           STOPPED
 * ```
 *
 * [FAILED] and [STOPPED] are terminal. A terminal session never transitions
 * back, even if late native events arrive.
 */
public enum class TunnelPhase {
    IDLE, STARTING, CONNECTING, ACTIVE, STOPPING, STOPPED, FAILED
}

/**
 * Feature a tunnel can provide. [PortalSnapshot.requestedCapabilities] is
 * derived from [PortalConfig]; [PortalSnapshot.readyCapabilities] is the
 * subset confirmed by ready native relay endpoints. HTTP/static require a
 * public URL; TCP and UDP each require their own advertised raw endpoint.
 * ACTIVE means every requested capability is ready, not just an HTTP lease.
 */
public enum class Capability {
    HTTP_TLS, STATIC_SITE, TCP, UDP, ECH, DISCOVERY, X402, OVERLAY
}

/**
 * Authoritative, monotonically revised view of a tunnel. UI should render
 * this; [PortalEvent]s are auxiliary notifications only.
 *
 * @property sessionId native tunnel identifier (stable for the session).
 * @property generation client-local generation distinguishing sessions that
 *   reuse a native tunnel id.
 * @property revision local monotonic revision; the v1 ABI carries no native
 *   revision, so ordering is "as observed by this client".
 * @property droppedEventCount events lost because no collector consumed the
 *   bounded event buffer in time.
 */
public data class PortalSnapshot(
    val sessionId: String,
    val generation: Int,
    val revision: Long,
    val phase: TunnelPhase,
    val requestedCapabilities: Set<Capability>,
    val readyCapabilities: Set<Capability>,
    val publicUrls: List<String>,
    val relays: List<PortalRelayStatus>,
    val lastFailure: PortalFailure?,
    val hasSecurityWarning: Boolean,
    val droppedEventCount: Long,
    val nativeStatus: PortalStatus? = null
) {
    /** Current relay-granted short URL when ACTIVE, else the canonical fallback. */
    val primaryPublicUrl: String?
        get() = (if (isActive) readyAliasUrl(relays) else null) ?: publicUrls.firstOrNull()
    val isActive: Boolean get() = phase == TunnelPhase.ACTIVE
    val isTerminal: Boolean get() = phase == TunnelPhase.STOPPED || phase == TunnelPhase.FAILED
}

/**
 * Typed metadata for [PortalTunnel.updateMetadata]. Serialized with the same
 * wire keys as [PortalConfig]'s metadata fields.
 */
@Serializable
public data class PortalMetadata(
    @SerialName("description") val description: String? = null,
    @SerialName("tags") val tags: List<String>? = null,
    @SerialName("owner") val owner: String? = null,
    @SerialName("thumbnail") val thumbnail: String? = null,
    @SerialName("hide") val hide: Boolean? = null
)
