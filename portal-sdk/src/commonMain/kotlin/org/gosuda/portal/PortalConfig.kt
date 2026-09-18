package org.gosuda.portal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * HTTP route aggregation rule. Routes are evaluated by the tunnel runtime to
 * dispatch incoming requests to an upstream server or a static directory.
 */
@Serializable
public data class PortalHTTPRoute(
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
public data class PortalX402Config(
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
public data class PortalConfig(
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
    public class Builder {
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

        public fun setName(name: String): Builder = apply { this.name = name }
        public fun setIdentityJson(json: String): Builder = apply { this.identityJson = json }
        public fun setIdentityPath(path: String): Builder = apply { this.identityPath = path }
        public fun addRelay(url: String): Builder = apply { this.relays.add(url) }
        public fun setRelays(urls: List<String>): Builder = apply { this.relays = urls.toMutableList() }
        public fun setDiscovery(enabled: Boolean): Builder = apply { this.discovery = enabled }
        public fun setMaxActiveRelays(max: Int): Builder = apply { this.maxActiveRelays = max }
        public fun setBanMitm(enabled: Boolean): Builder = apply { this.banMitm = enabled }
        public fun setEch(enabled: Boolean): Builder = apply { this.ech = enabled }
        public fun setOverlay(enabled: Boolean): Builder = apply { this.overlay = enabled }
        public fun setUdp(enabled: Boolean): Builder = apply { this.udp = enabled }
        public fun setTcp(enabled: Boolean): Builder = apply { this.tcp = enabled }
        public fun setDescription(desc: String): Builder = apply { this.description = desc }
        public fun addTag(tag: String): Builder = apply { this.tags.add(tag) }
        public fun setTags(tags: List<String>): Builder = apply { this.tags = tags.toMutableList() }
        public fun setOwner(owner: String): Builder = apply { this.owner = owner }
        public fun setThumbnail(url: String): Builder = apply { this.thumbnail = url }
        public fun setHide(hide: Boolean): Builder = apply { this.hide = hide }
        public fun setStaticSite(dir: String, index: String = "index.html"): Builder = apply {
            this.staticDir = dir
            this.staticIndex = index
        }
        public fun setTargetAddress(addr: String): Builder = apply { this.targetAddr = addr }
        public fun setUdpAddress(addr: String): Builder = apply { this.udpAddr = addr }
        public fun addHttpRoute(route: PortalHTTPRoute): Builder = apply { this.httpRoutes.add(route) }
        public fun setX402(config: PortalX402Config): Builder = apply { this.x402 = config }

        public fun build(): PortalConfig = PortalConfig(
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

    /**
     * Intent-oriented constructors for the common exposure modes. Each
     * factory sets only the fields its mode requires; every other field keeps
     * the wire default. Validation is unchanged: the returned config flows
     * through the same `ConfigValidation` checks as a hand-built one.
     */
    public companion object {
        /** Exposes a loopback HTTP server (`target_addr`). */
        public fun http(targetAddress: String, name: String? = null): PortalConfig =
            PortalConfig(name = name, targetAddr = targetAddress)

        /** Exposes prefix-routed HTTP upstreams and/or static roots. */
        public fun routes(routes: List<PortalHTTPRoute>, name: String? = null): PortalConfig =
            PortalConfig(name = name, httpRoutes = routes)

        /** Exposes the app's raw TCP listener. */
        public fun tcp(name: String? = null): PortalConfig =
            PortalConfig(name = name, tcp = true)

        /** Exposes a loopback UDP listener (`udp` + `udp_addr`). */
        public fun udp(targetAddress: String, name: String? = null): PortalConfig =
            PortalConfig(name = name, udp = true, udpAddr = targetAddress)

        /** Serves a directory without an embedded HTTP server. */
        public fun staticSite(
            directory: String,
            index: String = "index.html",
            name: String? = null
        ): PortalConfig =
            PortalConfig(name = name, staticDir = directory, staticIndex = index)
    }
}

/**
 * Kotlin DSL entry point mirroring [PortalConfig.Builder]:
 * `portalConfig { setName("x"); setDiscovery(true) }`.
 */
public fun portalConfig(block: PortalConfig.Builder.() -> Unit): PortalConfig =
    PortalConfig.Builder().apply(block).build()
