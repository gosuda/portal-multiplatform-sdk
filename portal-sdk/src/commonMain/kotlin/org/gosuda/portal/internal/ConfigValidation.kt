package org.gosuda.portal.internal

import org.gosuda.portal.Capability
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalException
import org.gosuda.portal.PortalFailure
import org.gosuda.portal.PortalHTTPRoute

/**
 * Input validation applied before any native call. Rules follow
 * docs/DESIGN_RULES.md; violations fail fast with
 * [PortalFailure.Codes.INVALID_CONFIG] / [PortalFailure.Codes.UNSUPPORTED_CAPABILITY].
 */
internal object ConfigValidation {
    private const val MAX_RELAYS = 64
    private const val MAX_TAGS = 16
    private const val MAX_TAG_LEN = 64
    private const val MAX_DESCRIPTION_LEN = 512
    private const val MAX_OWNER_LEN = 128
    private const val MAX_THUMBNAIL_LEN = 2048
    private const val MAX_METADATA_JSON_BYTES = 16 * 1024

    private val urlPattern = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/?#]*)([^?#]*)(?:\\?[^#]*)?(?:#.*)?$")
    private val amountPattern = Regex("^\\d+(\\.\\d{1,18})?$")

    fun validate(
        config: PortalConfig,
        allowRemoteTargets: Boolean,
        supported: Set<Capability>
    ) {
        if (!config.identityJson.isNullOrEmpty() && !config.identityPath.isNullOrEmpty()) {
            fail("identity_json and identity_path are mutually exclusive")
        }
        if (config.maxActiveRelays !in 0..MAX_RELAYS) {
            fail("max_active_relays must be in 0..$MAX_RELAYS (0 = engine default), got ${config.maxActiveRelays}")
        }

        val relays = config.relays.orEmpty()
        if (config.discovery == false && relays.isEmpty()) {
            fail("discovery=false requires at least one explicit relay")
        }
        relays.forEach { validateRelayUrl(it) }

        config.staticDir?.let { dir ->
            if (dir.isBlank()) fail("static_dir must not be blank")
            if (dir.split('/', '\\').any { it == ".." }) {
                fail("static_dir must not contain '..' path segments")
            }
        }
        config.staticIndex?.let { index ->
            if (index.isBlank() || index.contains('/') || index.contains('\\') || index == "..") {
                fail("static_index must be a plain file name")
            }
        }

        config.targetAddr?.let { validateTarget(it, "target_addr", allowRemoteTargets) }
        config.udpAddr?.let { validateTarget(it, "udp_addr", allowRemoteTargets) }

        config.httpRoutes.orEmpty().forEach { validateRoute(it) }

        if ((config.description?.length ?: 0) > MAX_DESCRIPTION_LEN) {
            fail("description exceeds $MAX_DESCRIPTION_LEN characters")
        }
        if ((config.owner?.length ?: 0) > MAX_OWNER_LEN) {
            fail("owner exceeds $MAX_OWNER_LEN characters")
        }
        if ((config.thumbnail?.length ?: 0) > MAX_THUMBNAIL_LEN) {
            fail("thumbnail exceeds $MAX_THUMBNAIL_LEN characters")
        }
        val tags = config.tags.orEmpty()
        if (tags.size > MAX_TAGS || tags.any { it.length > MAX_TAG_LEN }) {
            fail("tags are limited to $MAX_TAGS entries of $MAX_TAG_LEN characters")
        }

        // Capability pre-check: never silently ignore an unsupported option.
        val requested = requestedCapabilities(config)
        val unsupported = requested - supported
        if (unsupported.isNotEmpty()) {
            throw PortalException(
                PortalFailure.Codes.UNSUPPORTED_CAPABILITY,
                "requested capabilities not supported by this engine: ${unsupported.joinToString()}"
            )
        }
    }

    fun requestedCapabilities(config: PortalConfig): Set<Capability> = buildSet {
        add(Capability.HTTP_TLS)
        if (config.staticDir != null || config.httpRoutes.orEmpty().any { it.staticRoot != null }) {
            add(Capability.STATIC_SITE)
        }
        if (config.tcp) add(Capability.TCP)
        if (config.udp) add(Capability.UDP)
        if (config.ech) add(Capability.ECH)
        if (config.discovery != false) add(Capability.DISCOVERY)
        if (config.x402 != null) add(Capability.X402)
        if (config.overlay) add(Capability.OVERLAY)
    }

    /**
     * Mirrors `utils.NormalizeRelayURL`: trims, defaults a missing scheme to
     * `https://`, validates, and returns the canonical form — query and
     * fragment stripped, trailing slashes removed, and a trailing `/relay`
     * path segment dropped. Throws INVALID_CONFIG on malformed input.
     */
    fun normalizeRelayUrl(url: String): String {
        var candidate = url.trim()
        if (candidate.isEmpty()) fail("relay url is empty")
        if (!candidate.contains("://")) {
            candidate = "https://" + candidate.removePrefix("//")
        }
        val parsed = parseRelayUrl(candidate, url)
        var path = parsed.path.trimEnd('/')
        // Upstream checks the lowercase suffix but trims the literal one:
        // only an exact trailing "/relay" is dropped.
        if (path.endsWith("/relay")) {
            path = path.dropLast("/relay".length)
        }
        return "${parsed.scheme}://${parsed.authority}$path"
    }

    /**
     * Mirrors `utils.NormalizeRelayURL` in portal-tunnel: https only, bare
     * hosts default to https, `http` is accepted only for loopback hosts
     * (the engine upgrades it), credentials and invalid ports are rejected.
     */
    private fun validateRelayUrl(url: String) {
        var candidate = url.trim()
        if (candidate.isEmpty()) fail("relay url is empty")
        if (!candidate.contains("://")) {
            candidate = "https://" + candidate.removePrefix("//")
        }
        parseRelayUrl(candidate, url)
    }

    private data class ParsedRelayUrl(val scheme: String, val authority: String, val path: String)

    private fun parseRelayUrl(candidate: String, original: String): ParsedRelayUrl {
        val match = urlPattern.matchEntire(candidate)
            ?: fail("relay url is invalid: $original")
        val scheme = match.groupValues[1].lowercase()
        val authority = match.groupValues[2]
        val path = match.groupValues[3]
        if (authority.contains('@')) fail("relay url must not include credentials: $original")
        val host: String
        val port: String?
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            if (close < 0) fail("relay url has an invalid host: $original")
            host = authority.substring(1, close)
            val rest = authority.substring(close + 1)
            if (rest.isNotEmpty() && !rest.startsWith(':')) {
                fail("relay url has an invalid host: $original")
            }
            port = rest.removePrefix(":").ifEmpty { null }
        } else {
            host = authority.substringBeforeLast(':')
            port = authority.substringAfterLast(':', "").ifEmpty { null }
        }
        if (host.isEmpty() || host.contains(':')) fail("relay url has an invalid host: $original")
        if (authority.endsWith(':')) fail("relay url has an invalid port: $original")
        if (port != null) {
            val n = port.toIntOrNull() ?: fail("relay url port must be between 1 and 65535: $original")
            if (n !in 1..65535) fail("relay url port must be between 1 and 65535: $original")
        }
        if (scheme == "http" && isLocalRelayHost(host)) {
            return ParsedRelayUrl("https", authority, path) // upgraded to https upstream
        }
        if (scheme != "https") fail("relay url must use https: $original")
        return ParsedRelayUrl(scheme, authority, path)
    }

    /**
     * Mirrors `utils.IsLocalRelayHost`: localhost, *.localhost, and any
     * parsed loopback IP (127.0.0.0/8, ::1 in any compression, IPv4-mapped
     * ::ffff:127.x).
     */
    private fun isLocalRelayHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h.startsWith("127.") && isIpv4(h)) return true
        val groups = parseIpv6Groups(h) ?: return false
        // ::1 — all groups zero except the last.
        if (groups.take(7).all { it == 0 } && groups[7] == 1) return true
        // ::ffff:a.b.c.d — IPv4-mapped; loopback when the embedded IPv4 is.
        if (groups.take(5).all { it == 0 } && groups[5] == 0xffff) {
            val v4 = (groups[6] shl 16) or groups[7]
            return (v4 ushr 24) == 127
        }
        return false
    }

    private fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
                part.toInt() in 0..255
        }
    }

    /**
     * Parses an IPv6 literal (without brackets) into eight 16-bit groups.
     * Handles `::` compression and a trailing embedded IPv4 address.
     */
    private fun parseIpv6Groups(host: String): IntArray? {
        var input = host
        var embeddedV4: IntArray? = null
        val lastColon = input.lastIndexOf(':')
        if (lastColon >= 0 && input.substring(lastColon + 1).contains('.')) {
            val v4 = input.substring(lastColon + 1)
            if (!isIpv4(v4)) return null
            embeddedV4 = v4.split('.').map { it.toInt() }.toIntArray()
            input = input.substring(0, lastColon + 1) + "0:0"
        }
        val halves = input.split("::", limit = 2)
        if (halves.size == 2 && input.indexOf("::") != input.lastIndexOf("::")) return null
        val head = halves[0].split(':').filter { it.isNotEmpty() }
        val tail = if (halves.size == 2) halves[1].split(':').filter { it.isNotEmpty() } else emptyList()
        if (halves.size == 1 && head.size != 8) return null
        if (head.size + tail.size > 8) return null
        val groups = IntArray(8)
        fun parseGroup(s: String): Int? =
            if (s.length in 1..4 && s.all { it in '0'..'9' || it in 'a'..'f' }) s.toInt(16) else null
        head.forEachIndexed { i, s -> groups[i] = parseGroup(s) ?: return null }
        tail.forEachIndexed { i, s -> groups[8 - tail.size + i] = parseGroup(s) ?: return null }
        if (embeddedV4 != null) {
            groups[6] = (embeddedV4[0] shl 8) or embeddedV4[1]
            groups[7] = (embeddedV4[2] shl 8) or embeddedV4[3]
        }
        return groups
    }

    private fun validateTarget(addr: String, field: String, allowRemote: Boolean) {
        val host: String
        val port: String
        if (addr.startsWith('[')) {
            val close = addr.indexOf(']')
            if (close < 0) fail("$field has an invalid host: $addr")
            host = addr.substring(1, close)
            val rest = addr.substring(close + 1)
            if (!rest.startsWith(':')) fail("$field must include a port: $addr")
            port = rest.removePrefix(":")
        } else {
            host = addr.substringBeforeLast(':')
            port = addr.substringAfterLast(':', "")
        }
        if (host.isEmpty() || host.contains(':')) fail("$field has an invalid host: $addr")
        val n = port.toIntOrNull() ?: fail("$field must include a port between 1 and 65535: $addr")
        if (n !in 1..65535) fail("$field port must be between 1 and 65535: $addr")
        if (allowRemote) return
        val loopback = isLocalRelayHost(host) || host == "0.0.0.0" || host == "::"
        if (!loopback) {
            fail("$field must be a loopback address unless allowRemoteTargets is set: $addr")
        }
    }


    private fun validateRoute(route: PortalHTTPRoute) {
        if (!route.prefix.startsWith('/')) {
            fail("http route prefix must start with '/': ${route.prefix}")
        }
        if (route.upstream != null && route.staticRoot != null) {
            fail("http route '${route.prefix}' sets both upstream and static_root")
        }
        if (route.upstream == null && route.staticRoot == null) {
            fail("http route '${route.prefix}' needs upstream or static_root")
        }
        route.amount?.let {
            if (!amountPattern.matches(it)) fail("route amount must be a decimal string: $it")
        }
    }

    fun validateMetadataJson(json: String) {
        if (json.length > MAX_METADATA_JSON_BYTES) {
            fail("metadata exceeds $MAX_METADATA_JSON_BYTES bytes")
        }
    }

    private fun fail(message: String): Nothing =
        throw PortalException(PortalFailure.Codes.INVALID_CONFIG, message)
}
