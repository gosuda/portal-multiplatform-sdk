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

    private val urlPattern = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/?#]*)([^?#]*)(?:#.*)?$")
    private val amountPattern = Regex("^\\d+(\\.\\d{1,18})?$")

    fun validate(
        config: PortalConfig,
        allowInsecureLocalRelays: Boolean,
        allowRemoteTargets: Boolean,
        supported: Set<Capability>
    ) {
        if (!config.identityJson.isNullOrEmpty() && !config.identityPath.isNullOrEmpty()) {
            fail("identity_json and identity_path are mutually exclusive")
        }
        if (config.maxActiveRelays !in 1..MAX_RELAYS) {
            fail("max_active_relays must be in 1..$MAX_RELAYS, got ${config.maxActiveRelays}")
        }

        val relays = config.relays.orEmpty()
        if (config.discovery == false && relays.isEmpty()) {
            fail("discovery=false requires at least one explicit relay")
        }
        relays.forEach { validateRelayUrl(it, allowInsecureLocalRelays) }

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

    private fun validateRelayUrl(url: String, allowInsecure: Boolean) {
        val match = urlPattern.matchEntire(url)
            ?: fail("relay URL is malformed: $url")
        val scheme = match.groupValues[1].lowercase()
        val authority = match.groupValues[2]
        if (authority.contains('@')) fail("relay URL must not contain user-info: $url")
        val host = authority.substringBeforeLast(':').removeSurrounding("[", "]")
        if (host.isEmpty()) fail("relay URL has no host: $url")
        when (scheme) {
            "https", "wss" -> Unit
            "http", "ws" -> if (!allowInsecure) {
                fail("insecure relay scheme '$scheme' requires allowInsecureLocalRelays: $url")
            }
            else -> fail("unsupported relay scheme '$scheme': $url")
        }
    }

    private fun validateTarget(addr: String, field: String, allowRemote: Boolean) {
        if (allowRemote) return
        val host = addr.substringBeforeLast(':').removeSurrounding("[", "]")
        val loopback = host == "localhost" || host == "::1" ||
            host.startsWith("127.") || host == "0.0.0.0"
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
