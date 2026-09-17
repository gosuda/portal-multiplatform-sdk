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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.gosuda.portal.internal.ConfigValidation
import org.gosuda.portal.internal.PortalEventHub
import org.gosuda.portal.internal.PortalJson
import org.gosuda.portal.internal.PortalNativeEngine
import org.gosuda.portal.internal.platformNativeEngine

/**
 * Read-only diagnostic snapshot of a client. Never contains identity
 * documents, keys, tokens, or request bodies.
 */
public data class PortalDiagnostics(
    val sdkVersion: String,
    val abiVersion: Int,
    val wireSchemaVersion: Int,
    val activeSessions: Int,
    val droppedOrphanEvents: Long,
    val sessions: List<SessionDiagnostics>
) {
    public data class SessionDiagnostics(
        val sessionId: String,
        val generation: Int,
        val phase: TunnelPhase,
        val revision: Long,
        val droppedEvents: Long
    )
}

/**
 * Owner of tunnel sessions. A client serializes native operations, receives
 * events routed by the process-global [PortalEventHub], and guarantees that
 * sessions it opened are closed by [close] — never by unrelated clients.
 *
 * Construct once per app (or per long-lived component) and keep it alive for
 * the tunnels' lifetime; the screen observing a tunnel must not own it.
 *
 * @param allowRemoteTargets permits non-loopback `target_addr`/`udp_addr`.
 *   Keep false unless the app intentionally proxies to remote hosts.
 */
@OptIn(ExperimentalAtomicApi::class)
public class PortalClient internal constructor(
    internal val engine: PortalNativeEngine,
    private val allowRemoteTargets: Boolean = false
) {
    /**
     * Creates a client backed by the platform `libportaltunnel` engine.
     * Tests inject a fake engine through the internal primary constructor.
     */
    public constructor(
        allowRemoteTargets: Boolean = false
    ) : this(platformNativeEngine(), allowRemoteTargets)

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val closed = AtomicBoolean(false)
    private val tunnels = AtomicReference<Map<String, PortalTunnel>>(emptyMap())
    private val generationCounter = AtomicLong(0)

    private val _events = MutableSharedFlow<PortalEvent>(extraBufferCapacity = EVENT_BUFFER)

    /**
     * Aggregated events from every session this client owns. Bounded and not
     * replayed; per-session state lives on [PortalTunnel.state].
     */
    public val events: SharedFlow<PortalEvent> = _events.asSharedFlow()

    /** Capabilities the bundled v1 engine can nominally provide. */
    public fun capabilities(): Set<Capability> = SUPPORTED_CAPABILITIES

    /**
     * Starts a tunnel and returns its handle once ownership is registered.
     * Success means the native runtime accepted the start request — use
     * [PortalTunnel.awaitReady] or [PortalTunnel.state] for actual readiness.
     *
     * Cancellation after the native start rolls the session back: the native
     * handle is stopped before the exception propagates.
     */
    public suspend fun open(config: PortalConfig): PortalTunnel {
        ensureOpen()
        ConfigValidation.validate(config, allowRemoteTargets, capabilities())
        PortalEventHub.install(engine)

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
        // The client may have been closed while the native start was in
        // flight; a session registered on a dead owner would be orphaned.
        if (closed.load()) {
            withContext(NonCancellable) {
                runCatching { withContext(Dispatchers.Default) { engine.stop(tunnelId) } }
            }
            throw PortalException(PortalFailure.Codes.CLIENT_CLOSED, "client is closed")
        }

        val tunnel = PortalTunnel(
            tunnelId = tunnelId,
            config = config,
            owner = this,
            generation = (generationCounter.addAndFetch(1)).toInt()
        )
        PortalEventHub.register(tunnel)
        tunnels.update { it + (tunnelId to tunnel) }

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
    public suspend fun close() {
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

    public fun diagnostics(): PortalDiagnostics {
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
            droppedOrphanEvents = PortalEventHub.droppedOrphanEvents,
            sessions = sessions
        )
    }

    // ---- internal plumbing -------------------------------------------------

    internal val nativeEngine: PortalNativeEngine get() = engine

    /** Called by the hub after a tunnel reduced a raw event. */
    internal fun onTunnelEvent(event: PortalEvent) {
        _events.tryEmit(event)
    }

    internal fun unregisterTunnel(tunnelId: String) {
        tunnels.update { it - tunnelId }
        PortalEventHub.unregister(tunnelId)
    }

    private fun ensureOpen() {
        if (closed.load()) {
            throw PortalException(PortalFailure.Codes.CLIENT_CLOSED, "client is closed")
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
        }
    }

    private companion object {
        const val EVENT_BUFFER = 128

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
