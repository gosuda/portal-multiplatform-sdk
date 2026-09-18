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
}
