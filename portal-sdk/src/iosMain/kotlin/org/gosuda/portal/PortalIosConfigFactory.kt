package org.gosuda.portal

/**
 * Swift-facing entry point for the intent-oriented [PortalConfig] factories.
 *
 * The common factories live on `PortalConfig`'s companion object, whose
 * generated Swift spelling is awkward (`PortalConfig.companion.http(...)`).
 * This facade delegates to them so Swift callers get a short, stable name —
 * it adds no field mapping of its own.
 */
public object PortalIosConfigFactory {

    /** Exposes a loopback HTTP server (`target_addr`). */
    public fun http(targetAddress: String, name: String?): PortalConfig =
        PortalConfig.http(targetAddress, name)

    /** Exposes prefix-routed HTTP upstreams and/or static roots. */
    public fun routes(routes: List<PortalHTTPRoute>, name: String?): PortalConfig =
        PortalConfig.routes(routes, name)

    /** Exposes the app's raw TCP listener. */
    public fun tcp(name: String?): PortalConfig =
        PortalConfig.tcp(name)

    /** Exposes a loopback UDP listener (`udp` + `udp_addr`). */
    public fun udp(targetAddress: String, name: String?): PortalConfig =
        PortalConfig.udp(targetAddress, name)

    /** Serves a directory without an embedded HTTP server. */
    public fun staticSite(directory: String, index: String, name: String?): PortalConfig =
        PortalConfig.staticSite(directory, index, name)

    /**
     * Advanced escape hatch: builds a config with every field the
     * intent factories do not cover, without the generated all-fields
     * `PortalConfig` initializer or `KotlinBoolean` at the Swift call site.
     * Boolean parameters are
     * non-nullable on purpose: `encodeDefaults=false` makes an explicit
     * `true`/`false` wire-identical to the field default, so there is no
     * "unset" state to preserve. Pass null only for the `String?`/`List?`/
     * object fields.
     */
    public fun custom(
        name: String?,
        identityJson: String?,
        identityPath: String?,
        relays: List<String>?,
        discovery: Boolean,
        maxActiveRelays: Int,
        banMitm: Boolean,
        ech: Boolean,
        udp: Boolean,
        tcp: Boolean,
        description: String?,
        tags: List<String>?,
        owner: String?,
        thumbnail: String?,
        hide: Boolean,
        staticDir: String?,
        staticIndex: String?,
        targetAddr: String?,
        udpAddr: String?,
        httpRoutes: List<PortalHTTPRoute>?,
        x402: PortalX402Config?
    ): PortalConfig = PortalConfig(
        name = name,
        identityJson = identityJson,
        identityPath = identityPath,
        relays = relays,
        discovery = discovery,
        maxActiveRelays = maxActiveRelays,
        banMitm = banMitm,
        ech = ech,
        udp = udp,
        tcp = tcp,
        description = description,
        tags = tags,
        owner = owner,
        thumbnail = thumbnail,
        hide = hide,
        staticDir = staticDir,
        staticIndex = staticIndex,
        targetAddr = targetAddr,
        udpAddr = udpAddr,
        httpRoutes = httpRoutes,
        x402 = x402
    )
}
