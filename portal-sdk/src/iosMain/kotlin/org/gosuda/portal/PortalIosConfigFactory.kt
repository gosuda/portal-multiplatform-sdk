package org.gosuda.portal

/**
 * Swift-facing entry point for the intent-oriented [PortalConfig] factories.
 *
 * The common factories live on `PortalConfig`'s companion object, whose
 * generated Swift spelling is awkward (`PortalConfig.companion.http(...)`).
 * This facade exposes short, stable config and route constructors without
 * requiring Swift callers to supply Kotlin's optional/default fields.
 */
public object PortalIosConfigFactory {

    /** Exposes a loopback HTTP server with the SDK defaults. */
    public fun http(targetAddress: String): PortalConfig =
        PortalConfig.http(targetAddress)

    /** Exposes a loopback HTTP server (`target_addr`). */
    public fun http(targetAddress: String, name: String?): PortalConfig =
        PortalConfig.http(targetAddress, name)

    /** Exposes prefix routes with the SDK defaults. */
    public fun routes(routes: List<PortalHTTPRoute>): PortalConfig =
        PortalConfig.routes(routes)

    /** Exposes prefix-routed HTTP upstreams and/or static roots. */
    public fun routes(routes: List<PortalHTTPRoute>, name: String?): PortalConfig =
        PortalConfig.routes(routes, name)

    /** Routes a path prefix to a loopback HTTP upstream. */
    public fun httpRoute(prefix: String, upstream: String): PortalHTTPRoute =
        PortalHTTPRoute(prefix = prefix, upstream = upstream)

    /** Routes a path prefix to static files with the native default index. */
    public fun staticRoute(prefix: String, directory: String): PortalHTTPRoute =
        PortalHTTPRoute(prefix = prefix, staticRoot = directory)

    /** Exposes a loopback TCP listener with the SDK defaults. */
    public fun tcp(targetAddress: String): PortalConfig =
        PortalConfig.tcp(targetAddress)

    /** Exposes a loopback TCP listener (`tcp` + `target_addr`). */
    public fun tcp(targetAddress: String, name: String?): PortalConfig =
        PortalConfig.tcp(targetAddress, name)

    /** Exposes a loopback UDP listener with the SDK defaults. */
    public fun udp(targetAddress: String): PortalConfig =
        PortalConfig.udp(targetAddress)

    /** Exposes a loopback UDP listener (`udp` + `udp_addr`). */
    public fun udp(targetAddress: String, name: String?): PortalConfig =
        PortalConfig.udp(targetAddress, name)

    /** Serves a directory with the default `index.html`. */
    public fun staticSite(directory: String): PortalConfig =
        PortalConfig.staticSite(directory)

    /** Serves a directory without an embedded HTTP server. */
    public fun staticSite(directory: String, index: String, name: String?): PortalConfig =
        PortalConfig.staticSite(directory, index, name)

    /**
     * Advanced escape hatch for explicit identity, relays, metadata and transport
     * combinations without the generated all-fields initializer. Runtime domain
     * labels use [PortalConfig.withDomainPrefix] on any returned config.
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
