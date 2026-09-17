package org.gosuda.portal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * HTTP route aggregation rule. Routes are evaluated by the tunnel runtime to
 * dispatch incoming requests to an upstream server or a static directory.
 */
@Serializable
data class PortalHTTPRoute(
    @SerialName("prefix") val prefix: String,
    @SerialName("upstream") val upstream: String? = null,
    @SerialName("static_root") val staticRoot: String? = null,
    @SerialName("static_index") val staticIndex: String? = null,
    @SerialName("methods") val methods: List<String>? = null,
    @SerialName("amount") val amount: String? = null
)

/**
 * x402 micropayment configuration (Sui USDC, Casper wCSPR).
 */
@Serializable
data class PortalX402Config(
    @SerialName("pay_to") val payTo: String,
    @SerialName("testnet") val testnet: Boolean = false,
    @SerialName("network") val network: String? = null,
    @SerialName("asset") val asset: String? = null,
    @SerialName("endpoints") val endpoints: List<String>? = null,
    @SerialName("facilitator_token") val facilitatorToken: String? = null
)

/**
 * Tunnel configuration. Serialized to JSON and handed to the native
 * `libportaltunnel` runtime via `PortalStart`.
 *
 * Field names and defaults are kept in lockstep with the Android, iOS and
 * Flutter Portal SDKs so a config produces identical behavior everywhere.
 */
@Serializable
data class PortalConfig(
    @SerialName("name") val name: String? = null,
    @SerialName("identity_json") val identityJson: String? = null,
    @SerialName("identity_path") val identityPath: String? = null,
    @SerialName("relays") val relays: List<String>? = null,
    @SerialName("discovery") val discovery: Boolean? = true,
    @SerialName("max_active_relays") val maxActiveRelays: Int = 3,
    @SerialName("ban_mitm") val banMitm: Boolean = false,
    @SerialName("ech") val ech: Boolean = false,
    @SerialName("overlay") val overlay: Boolean = false,
    @SerialName("udp") val udp: Boolean = false,
    @SerialName("tcp") val tcp: Boolean = false,
    @SerialName("description") val description: String? = null,
    @SerialName("tags") val tags: List<String>? = null,
    @SerialName("owner") val owner: String? = null,
    @SerialName("thumbnail") val thumbnail: String? = null,
    @SerialName("hide") val hide: Boolean = false,
    @SerialName("static_dir") val staticDir: String? = null,
    @SerialName("static_index") val staticIndex: String? = null,
    @SerialName("target_addr") val targetAddr: String? = null,
    @SerialName("udp_addr") val udpAddr: String? = null,
    @SerialName("http_routes") val httpRoutes: List<PortalHTTPRoute>? = null,
    @SerialName("x402") val x402: PortalX402Config? = null
) {
    /**
     * Fluent builder mirroring `PortalConfig.Builder` in the Android SDK,
     * for Java callers and parity with existing Portal codebases.
     */
    class Builder {
        private var name: String? = null
        private var identityJson: String? = null
        private var identityPath: String? = null
        private var relays: MutableList<String> = mutableListOf()
        private var discovery: Boolean? = true
        private var maxActiveRelays: Int = 3
        private var banMitm: Boolean = false
        private var ech: Boolean = false
        private var overlay: Boolean = false
        private var udp: Boolean = false
        private var tcp: Boolean = false
        private var description: String? = null
        private var tags: MutableList<String> = mutableListOf()
        private var owner: String? = null
        private var thumbnail: String? = null
        private var hide: Boolean = false
        private var staticDir: String? = null
        private var staticIndex: String? = null
        private var targetAddr: String? = null
        private var udpAddr: String? = null
        private var httpRoutes: MutableList<PortalHTTPRoute> = mutableListOf()
        private var x402: PortalX402Config? = null

        fun setName(name: String) = apply { this.name = name }
        fun setIdentityJson(json: String) = apply { this.identityJson = json }
        fun setIdentityPath(path: String) = apply { this.identityPath = path }
        fun addRelay(url: String) = apply { this.relays.add(url) }
        fun setRelays(urls: List<String>) = apply { this.relays = urls.toMutableList() }
        fun setDiscovery(enabled: Boolean) = apply { this.discovery = enabled }
        fun setMaxActiveRelays(max: Int) = apply { this.maxActiveRelays = max }
        fun setBanMitm(enabled: Boolean) = apply { this.banMitm = enabled }
        fun setEch(enabled: Boolean) = apply { this.ech = enabled }
        fun setOverlay(enabled: Boolean) = apply { this.overlay = enabled }
        fun setUdp(enabled: Boolean) = apply { this.udp = enabled }
        fun setTcp(enabled: Boolean) = apply { this.tcp = enabled }
        fun setDescription(desc: String) = apply { this.description = desc }
        fun addTag(tag: String) = apply { this.tags.add(tag) }
        fun setTags(tags: List<String>) = apply { this.tags = tags.toMutableList() }
        fun setOwner(owner: String) = apply { this.owner = owner }
        fun setThumbnail(url: String) = apply { this.thumbnail = url }
        fun setHide(hide: Boolean) = apply { this.hide = hide }
        fun setStaticSite(dir: String, index: String = "index.html") = apply {
            this.staticDir = dir
            this.staticIndex = index
        }
        fun setTargetAddress(addr: String) = apply { this.targetAddr = addr }
        fun setUdpAddress(addr: String) = apply { this.udpAddr = addr }
        fun addHttpRoute(route: PortalHTTPRoute) = apply { this.httpRoutes.add(route) }
        fun setX402(config: PortalX402Config) = apply { this.x402 = config }

        fun build() = PortalConfig(
            name = name,
            identityJson = identityJson,
            identityPath = identityPath,
            relays = relays.toList().takeIf { it.isNotEmpty() },
            discovery = discovery,
            maxActiveRelays = maxActiveRelays,
            banMitm = banMitm,
            ech = ech,
            overlay = overlay,
            udp = udp,
            tcp = tcp,
            description = description,
            tags = tags.toList().takeIf { it.isNotEmpty() },
            owner = owner,
            thumbnail = thumbnail,
            hide = hide,
            staticDir = staticDir,
            staticIndex = staticIndex,
            targetAddr = targetAddr,
            udpAddr = udpAddr,
            httpRoutes = httpRoutes.toList().takeIf { it.isNotEmpty() },
            x402 = x402
        )
    }
}
