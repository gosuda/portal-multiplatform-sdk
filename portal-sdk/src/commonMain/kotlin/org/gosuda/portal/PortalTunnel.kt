package org.gosuda.portal

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gosuda.portal.internal.ConfigValidation
import org.gosuda.portal.internal.PortalJson
import org.gosuda.portal.internal.PortalNativeEngine

/**
 * Handle to one running tunnel session, owned by the [PortalClient] that
 * opened it. All native operations are serialized per session; state updates
 * are applied through a single reducer so a delayed response can never
 * overwrite a newer snapshot.
 */
@OptIn(ExperimentalAtomicApi::class)
public class PortalTunnel internal constructor(
    public val tunnelId: String,
    public val config: PortalConfig,
    internal val owner: PortalClient,
    internal val generation: Int
) {
    private val engine: PortalNativeEngine get() = owner.nativeEngine

    private val requestedCaps = ConfigValidation.requestedCapabilities(config)

    private val _state = MutableStateFlow(
        PortalSnapshot(
            sessionId = tunnelId,
            generation = generation,
            revision = 0,
            phase = TunnelPhase.STARTING,
            requestedCapabilities = requestedCaps,
            readyCapabilities = emptySet(),
            publicUrls = emptyList(),
            relays = emptyList(),
            lastFailure = null,
            hasSecurityWarning = false,
            droppedEventCount = 0
        )
    )

    /** Authoritative session state. Safe to collect from any thread. */
    public val state: StateFlow<PortalSnapshot> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PortalEvent>(extraBufferCapacity = EVENT_BUFFER)

    /**
     * Auxiliary event stream (bounded, no replay). Late subscribers do not
     * receive past events; durable information lives in [state].
     */
    public val events: SharedFlow<PortalEvent> = _events.asSharedFlow()

    /** True while the session is in [TunnelPhase.ACTIVE]. */
    public val isActive: Boolean get() = state.value.phase == TunnelPhase.ACTIVE

    /** Tunnel name from the latest native status, or the configured name. */
    public val name: String? get() = state.value.nativeStatus?.name ?: config.name

    /** Identity address from the latest native status, if reported. */
    public val address: String? get() = state.value.nativeStatus?.address

    private val opsMutex = Mutex()
    private val revisionCounter = AtomicLong(0)
    private val droppedEvents = AtomicLong(0)
    private val stopRequested = AtomicBoolean(false)

    /**
     * Suspends until [capability] is ready or [timeoutMillis] elapses.
     * @throws PortalException UNSUPPORTED_CAPABILITY if the capability was not
     *   requested; STOP_TIMEOUT on timeout; TUNNEL_CLOSED if the session ends
     *   first. Cancellation propagates as `CancellationException`.
     */
    public suspend fun awaitReady(capability: Capability, timeoutMillis: Long = 30_000): PortalSnapshot {
        if (capability !in requestedCaps) {
            throw PortalException(
                PortalFailure.Codes.UNSUPPORTED_CAPABILITY,
                "capability $capability was not requested by this tunnel's config"
            )
        }
        try {
            return withTimeout(timeoutMillis) {
                state.first { snapshot ->
                    if (snapshot.isTerminal) {
                        throw PortalException(
                            PortalFailure.Codes.TUNNEL_CLOSED,
                            "tunnel reached ${snapshot.phase} before $capability became ready"
                        )
                    }
                    capability in snapshot.readyCapabilities
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw PortalException(
                PortalFailure.Codes.STOP_TIMEOUT,
                "capability $capability not ready within ${timeoutMillis}ms",
                retryable = true
            )
        }
    }
    /**
     * Suspends until the session reports ACTIVE or [timeoutMillis] elapses.
     * Equivalent to awaiting every requested capability.
     */
    public suspend fun awaitActive(timeoutMillis: Long = 30_000): PortalSnapshot {
        try {
            return withTimeout(timeoutMillis) {
                state.first { snapshot ->
                    if (snapshot.isTerminal) {
                        throw PortalException(
                            PortalFailure.Codes.TUNNEL_CLOSED,
                            "tunnel reached ${snapshot.phase} before becoming active"
                        )
                    }
                    snapshot.phase == TunnelPhase.ACTIVE
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw PortalException(
                PortalFailure.Codes.STOP_TIMEOUT,
                "tunnel not active within ${timeoutMillis}ms",
                retryable = true
            )
        }
    }

    /** Fetches the authoritative native status and merges it into [state]. */
    public suspend fun refresh(): PortalSnapshot {
        ensureUsable("refresh")
        return opsMutex.withLock {
            try {
                val raw = nativeCall("getStatus") { engine.getStatus(tunnelId) }
                val status = decodeStatus(raw)
                mergeStatus(status)
            } catch (e: PortalException) {
                _state.update { it.copy(lastFailure = e.failure, revision = nextRevision()) }
                throw e
            }
            _state.value
        }
    }

    public suspend fun addRelay(relayUrl: String) {
        ensureUsable("addRelay")
        opsMutex.withLock {
            nativeCall("addRelay") { engine.addRelay(tunnelId, relayUrl) }
        }
    }

    public suspend fun removeRelay(relayUrl: String) {
        ensureUsable("removeRelay")
        opsMutex.withLock {
            nativeCall("removeRelay") { engine.removeRelay(tunnelId, relayUrl) }
        }
    }

    /**
     * Updates public metadata (description, tags, owner, thumbnail, hide)
     * without restarting the session.
     */
    public suspend fun updateMetadata(metadata: PortalMetadata) {
        ensureUsable("updateMetadata")
        val json = PortalJson.encodeToString(metadata)
        ConfigValidation.validateMetadataJson(json)
        opsMutex.withLock {
            nativeCall("updateMetadata") { engine.updateMetadata(tunnelId, json) }
        }
    }

    /**
     * Stops the session. Transitions to STOPPING first; on native success the
     * terminal snapshot is recorded and the handle is unregistered. On native
     * failure the handle stays registered in STOPPING and the call may be
     * retried — ownership is not released while the native side may still be
     * alive. Concurrent calls converge on the same outcome.
     */
    public suspend fun stop() {
        while (true) {
            if (state.value.isTerminal) return
            if (stopRequested.compareAndSet(false, true)) break
            // Another stop is in flight; wait for its outcome, then re-check.
            opsMutex.withLock { }
        }
        _state.update { it.copy(phase = TunnelPhase.STOPPING, revision = nextRevision()) }
        try {
            opsMutex.withLock {
                nativeCall("stop") { engine.stop(tunnelId) }
            }
        } catch (e: CancellationException) {
            stopRequested.store(false)
            throw e
        } catch (e: PortalException) {
            // The native side may still be alive: keep the handle registered,
            // stay in STOPPING, and allow the caller to retry.
            stopRequested.store(false)
            _state.update {
                it.copy(lastFailure = e.failure, revision = nextRevision())
            }
            throw e
        }
        markTerminal(TunnelPhase.STOPPED, null)
    }

    // ---- event ingress (called by the event hub) ----------------------------

    /**
     * Reduces one raw native event into state and returns the parsed event
     * for fan-out to [events] and the owning client's aggregate stream.
     */
    internal fun handleRawEvent(eventType: String, payloadJson: String): PortalEvent {
        val event = try {
            when (eventType) {
                "STATUS_CHANGED" -> {
                    val status = decodeStatus(payloadJson)
                    mergeStatus(status)
                    PortalEvent.StatusChanged(tunnelId, status)
                }
                "STARTED" -> {
                    val name = PortalJson.parseToJsonElement(payloadJson).jsonObject["name"]
                        ?.jsonPrimitive?.content.orEmpty()
                    _state.update {
                        if (it.isTerminal) it
                        else it.copy(
                            phase = if (it.phase == TunnelPhase.STARTING) TunnelPhase.CONNECTING else it.phase,
                            revision = nextRevision()
                        )
                    }
                    PortalEvent.Started(tunnelId, name)
                }
                "STOPPED" -> {
                    markTerminal(TunnelPhase.STOPPED, null)
                    PortalEvent.Stopped(tunnelId)
                }
                "MITM_SUSPECTED" -> {
                    val relay = PortalJson.parseToJsonElement(payloadJson).jsonObject["relay_url"]
                        ?.jsonPrimitive?.content.orEmpty()
                    _state.update {
                        it.copy(hasSecurityWarning = true, revision = nextRevision())
                    }
                    PortalEvent.MitmSuspected(tunnelId, relay)
                }
                "RELAY_ADDED" -> {
                    val relay = PortalJson.parseToJsonElement(payloadJson).jsonObject["relay_url"]
                        ?.jsonPrimitive?.content.orEmpty()
                    _state.update {
                        it.copy(relays = it.relays + PortalRelayStatus(relayUrl = relay, state = "added"),
                            revision = nextRevision())
                    }
                    PortalEvent.RelayAdded(tunnelId, relay)
                }
                "RELAY_REMOVED" -> {
                    val relay = PortalJson.parseToJsonElement(payloadJson).jsonObject["relay_url"]
                        ?.jsonPrimitive?.content.orEmpty()
                    _state.update {
                        it.copy(relays = it.relays.filterNot { r -> r.relayUrl == relay },
                            revision = nextRevision())
                    }
                    PortalEvent.RelayRemoved(tunnelId, relay)
                }
                "ERROR" -> {
                    val message = PortalJson.parseToJsonElement(payloadJson).jsonObject["error"]
                        ?.jsonPrimitive?.content.orEmpty()
                    _state.update {
                        if (it.isTerminal) it
                        else it.copy(
                            lastFailure = PortalFailure(
                                PortalFailure.Codes.PROTOCOL_ERROR, message, retryable = true
                            ),
                            revision = nextRevision()
                        )
                    }
                    PortalEvent.Error(tunnelId, message)
                }
                else -> PortalEvent.Unknown(tunnelId, eventType, payloadJson)
            }
        } catch (t: Throwable) {
            PortalEvent.Error(tunnelId, "failed to parse native event: ${t.message}")
        }
        if (_events.subscriptionCount.value == 0 || !_events.tryEmit(event)) {
            val dropped = droppedEvents.addAndFetch(1)
            _state.update { it.copy(droppedEventCount = dropped) }
        }
        return event
    }

    internal fun markTerminal(phase: TunnelPhase, failure: PortalFailure?) {
        _state.update {
            it.copy(
                phase = phase,
                lastFailure = failure ?: it.lastFailure,
                readyCapabilities = emptySet(),
                revision = nextRevision()
            )
        }
        owner.unregisterTunnel(tunnelId)
    }

    // ---- internals ----------------------------------------------------------

    private fun mergeStatus(status: PortalStatus) {
        _state.update { current ->
            // Terminal sessions never resurrect; STOPPING keeps its phase so
            // a late status cannot flicker the session back to ACTIVE.
            if (current.isTerminal || current.phase == TunnelPhase.STOPPING) return@update current
            val phase = if (status.active) TunnelPhase.ACTIVE else TunnelPhase.CONNECTING
            current.copy(
                phase = phase,
                readyCapabilities = if (phase == TunnelPhase.ACTIVE) requestedCaps else emptySet(),
                publicUrls = status.publicUrls,
                relays = status.relays,
                nativeStatus = status,
                revision = nextRevision()
            )
        }
    }

    private fun decodeStatus(raw: String): PortalStatus = try {
        PortalJson.decodeFromString<PortalStatus>(raw)
    } catch (e: Exception) {
        throw PortalException(
            PortalFailure.Codes.PROTOCOL_ERROR,
            "native status is not valid JSON: ${e.message}"
        )
    }

    private fun ensureUsable(operation: String) {
        if (state.value.isTerminal) {
            throw PortalException(
                PortalFailure.Codes.TUNNEL_CLOSED,
                "tunnel is ${state.value.phase}; $operation rejected"
            )
        }
    }

    private suspend fun <T> nativeCall(operation: String, block: () -> T): T =
        withContext(Dispatchers.Default) {
            try {
                block()
            } catch (e: PortalException) {
                throw e
            } catch (t: Throwable) {
                throw PortalException(
                    PortalFailure.Codes.INTERNAL_ERROR,
                    "native $operation failed: ${t.message}",
                    operation = operation
                )
            }
        }

    private fun nextRevision(): Long = revisionCounter.addAndFetch(1)

    private companion object {
        const val EVENT_BUFFER = 64
    }
}
