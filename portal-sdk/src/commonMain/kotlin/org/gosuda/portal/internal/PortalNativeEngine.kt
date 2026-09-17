package org.gosuda.portal.internal

import kotlinx.serialization.json.Json

/**
 * Shared JSON codec for the v1 wire contract. `encodeDefaults = false` is
 * load-bearing: the native runtime distinguishes absent fields from fields
 * carrying default values. Do not change without a golden-fixture review.
 */
internal val PortalJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

/**
 * Internal boundary to the `libportaltunnel` v1 C ABI.
 *
 * All functions are blocking and may be called from arbitrary threads.
 * Implementations must throw [org.gosuda.portal.PortalException]; a raw
 * platform error is wrapped by the caller-facing engine adapter.
 *
 * The v1 ABI merges create+start into [start] and offers a single
 * process-global event listener. Stronger contracts (observer quiescence,
 * native revisions, per-session callbacks) require the planned ABI v2 and are
 * tracked in docs/DESIGN_RULES.md.
 */
internal interface PortalNativeEngine {
    /**
     * Installs the process-global native event listener. Called at most once
     * per client; later calls replace the listener.
     */
    fun setEventListener(listener: (tunnelId: String, eventType: String, payloadJson: String) -> Unit)

    fun generateIdentity(name: String): String
    fun parseIdentity(identityJson: String): String

    /** Starts a tunnel; returns the native tunnel id. */
    fun start(configJson: String): String
    fun stop(tunnelId: String)
    fun stopAll()

    fun getStatus(tunnelId: String): String
    fun addRelay(tunnelId: String, relayUrl: String)
    fun removeRelay(tunnelId: String, relayUrl: String)
    fun updateMetadata(tunnelId: String, metadataJson: String)
}

/** Platform-provided engine backed by the real `libportaltunnel` binary. */
internal expect fun platformNativeEngine(): PortalNativeEngine
