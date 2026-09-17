package org.gosuda.portal

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.gosuda.portal.internal.ConfigValidation
import org.gosuda.portal.internal.PortalJson
import org.gosuda.portal.internal.PortalNativeEngine
import org.gosuda.portal.internal.platformNativeEngine

/**
 * Read-only diagnostic snapshot of a client. Never contains identity
 * documents, keys, tokens, or request bodies.
 */
data class PortalDiagnostics(
    val sdkVersion: String,
    val abiVersion: Int,
    val wireSchemaVersion: Int,
    val activeSessions: Int,
    val droppedOrphanEvents: Long,
    val sessions: List<SessionDiagnostics>
) {
    data class SessionDiagnostics(
        val sessionId: String,
        val generation: Int,
        val phase: TunnelPhase,
        val revision: Long,
        val droppedEvents: Long
    )
}

/**
 * Owner of tunnel sessions. A client serializes native operations, routes
 * native events to the owning session, and guarantees that sessions it opened
 * are closed by [close] — never by unrelated clients.
 *
 * Construct once per app (or per long-lived component) and keep it alive for
 * the tunnels' lifetime; the screen observing a tunnel must not own it.
 *
 * @param allowInsecureLocalRelays permits `http://`/`ws://` relay URLs for
 *   local relay development. Keep false in production.
 * @param allowRemoteTargets permits non-loopback `target_addr`/`udp_addr`.
 *   Keep false unless the app intentionally proxies to remote hosts.
 */
@OptIn(ExperimentalAtomicApi::class)
class PortalClient internal constructor(
    internal val engine: PortalNativeEngine,
    private val allowInsecureLocalRelays: Boolean = false,
    private val allowRemoteTargets: Boolean = false
) {
    /**
     * Creates a client backed by the platform `libportaltunnel` engine.
     * Tests inject a fake engine through the internal primary constructor.
     */
    constructor(
        allowInsecureLocalRelays: Boolean = false,
        allowRemoteTargets: Boolean = false
    ) : this(platformNativeEngine(), allowInsecureLocalRelays, allowRemoteTargets)

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val closed = AtomicBoolean(false)
    private val listenerInstalled = AtomicBoolean(false)
    private val tunnels = AtomicReference<Map<String, PortalTunnel>>(emptyMap())
    private val orphanEvents = AtomicReference<Map<String, List<RawNativeEvent>>>(emptyMap())
    private val generationCounter = AtomicLong(0)
    private val droppedOrphanCount = AtomicLong(0)

    fun capabilities(): Set<Capability> = SUPPORTED_CAPABILITIES

    /**
     * Starts a tunnel and returns its handle once ownership is registered.
     * Success means the native runtime accepted the start request — use
     * [PortalTunnel.awaitReady] or [PortalTunnel.state] for actual readiness.
     *
     * Cancellation after the native start rolls the session back: the native
     * handle is stopped before the exception propagates.
     */
    suspend fun open(config: PortalConfig): PortalTunnel {
        ensureOpen()
        ConfigValidation.validate(config, allowInsecureLocalRelays, allowRemoteTargets, capabilities())
        ensureListener()

        val configJson = PortalJson.encodeToString(config)

        // The native start runs on the owner scope so a caller cancellation
        // cannot orphan a created handle: if the await is cancelled after the
        // native side started, the cleanup job stops it (design doc L.4).
        val startJob = scope.async(Dispatchers.Default) { engine.start(configJson) }
        val tunnelId = try {
            startJob.await()
        } catch (e: CancellationException) {
            scope.launch(NonCancellable) {
                val orphanId = runCatching { startJob.await() }.getOrNull()
                if (orphanId != null) {
                    runCatching { engine.stop(orphanId) }
                }
            }
            throw e
        } catch (e: PortalException) {
            throw e
        } catch (t: Throwable) {
            throw PortalException(
                PortalFailure.Codes.INTERNAL_ERROR,
                "native start failed: ${t.message}",
                operation = "start"
            )
        }
        val tunnel = PortalTunnel(
            tunnelId = tunnelId,
            config = config,
            client = this,
            generation = (generationCounter.addAndFetch(1)).toInt()
        )
        registerTunnel(tunnel)

        try {
            // Reconcile state that may have been emitted between the native
            // start and registration (the v1 ABI has no create/attach split).
            tunnel.refresh()
        } catch (e: CancellationException) {
            rollbackStart(tunnel)
            throw e
        } catch (_: PortalException) {
            // Recorded on the snapshot; the tunnel stays usable.
        }
        return tunnel
    }

    /**
     * Stops every session owned by this client and releases the owner scope.
     * Idempotent. Sessions owned by other clients are unaffected.
     */
    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        val owned = tunnels.load().values.toList()
        var firstFailure: PortalException? = null
        for (tunnel in owned) {
            try {
                tunnel.stop()
            } catch (e: PortalException) {
                if (firstFailure == null) firstFailure = e
            }
        }
        scope.cancel()
        firstFailure?.let { throw it }
    }

    fun diagnostics(): PortalDiagnostics {
        val sessions = tunnels.load().values.map {
            val s = it.state.value
            PortalDiagnostics.SessionDiagnostics(
                sessionId = it.tunnelId,
                generation = it.generation,
                phase = s.phase,
                revision = s.revision,
                droppedEvents = s.droppedEventCount
            )
        }
        return PortalDiagnostics(
            sdkVersion = SDK_VERSION,
            abiVersion = ABI_VERSION,
            wireSchemaVersion = WIRE_SCHEMA_VERSION,
            activeSessions = sessions.size,
            droppedOrphanEvents = droppedOrphanCount.load(),
            sessions = sessions
        )
    }

    // ---- internal plumbing -------------------------------------------------

    internal val nativeEngine: PortalNativeEngine get() = engine

    internal fun registerTunnel(tunnel: PortalTunnel) {
        tunnels.update { it + (tunnel.tunnelId to tunnel) }
        drainOrphans(tunnel.tunnelId)
    }

    internal fun unregisterTunnel(tunnelId: String) {
        tunnels.update { it - tunnelId }
        orphanEvents.update { it - tunnelId }
    }

    internal fun dispatchRawEvent(tunnelId: String, eventType: String, payloadJson: String) {
        val tunnel = tunnels.load()[tunnelId]
        if (tunnel != null) {
            tunnel.handleRawEvent(eventType, payloadJson)
        } else {
            bufferOrphan(tunnelId, eventType, payloadJson)
        }
    }

    private fun ensureOpen() {
        if (closed.load()) {
            throw PortalException(PortalFailure.Codes.CLIENT_CLOSED, "client is closed")
        }
    }

    private fun ensureListener() {
        if (!listenerInstalled.compareAndSet(false, true)) return
        try {
            engine.setEventListener { tunnelId, eventType, payloadJson ->
                dispatchRawEvent(tunnelId, eventType, payloadJson)
            }
        } catch (t: Throwable) {
            listenerInstalled.store(false)
            throw t
        }
    }

    private fun bufferOrphan(tunnelId: String, eventType: String, payloadJson: String) {
        while (true) {
            val current = orphanEvents.load()
            val list = current[tunnelId]
            if (list == null && current.size >= MAX_ORPHAN_SESSIONS) {
                droppedOrphanCount.addAndFetch(1)
                return
            }
            val events = list.orEmpty()
            if (events.size >= MAX_ORPHAN_EVENTS_PER_SESSION) {
                droppedOrphanCount.addAndFetch(1)
                return
            }
            val next = current + (tunnelId to events + RawNativeEvent(eventType, payloadJson))
            if (orphanEvents.compareAndSet(current, next)) return
        }
    }

    private fun drainOrphans(tunnelId: String) {
        val pending = orphanEvents.load()[tunnelId] ?: return
        orphanEvents.update { it - tunnelId }
        val tunnel = tunnels.load()[tunnelId] ?: return
        for (event in pending) {
            tunnel.handleRawEvent(event.eventType, event.payloadJson)
        }
    }

    private suspend fun rollbackStart(tunnel: PortalTunnel) {
        withContext(NonCancellable) {
            try {
                withContext(Dispatchers.Default) { engine.stop(tunnel.tunnelId) }
            } catch (_: Throwable) {
                // Best effort: the caller never received the handle.
            }
            tunnel.markTerminal(TunnelPhase.STOPPED, null)
            unregisterTunnel(tunnel.tunnelId)
        }
    }

    private data class RawNativeEvent(val eventType: String, val payloadJson: String)

    private companion object {
        const val MAX_ORPHAN_SESSIONS = 32
        const val MAX_ORPHAN_EVENTS_PER_SESSION = 64

        const val SDK_VERSION = "0.1.0"
        const val ABI_VERSION = 1
        const val WIRE_SCHEMA_VERSION = 1

        val SUPPORTED_CAPABILITIES: Set<Capability> = setOf(
            Capability.HTTP_TLS,
            Capability.STATIC_SITE,
            Capability.TCP,
            Capability.UDP,
            Capability.ECH,
            Capability.DISCOVERY,
            Capability.X402
            // OVERLAY is engine-version dependent and excluded from the v1 contract.
        )
    }
}

@OptIn(ExperimentalAtomicApi::class)
private inline fun <T> AtomicReference<T>.update(crossinline transform: (T) -> T) {
    while (true) {
        val current = load()
        if (compareAndSet(current, transform(current))) return
    }
}
