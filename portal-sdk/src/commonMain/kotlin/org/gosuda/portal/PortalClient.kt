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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.gosuda.portal.internal.ConfigValidation
import org.gosuda.portal.internal.PortalEventHub
import org.gosuda.portal.internal.PortalJson
import org.gosuda.portal.internal.PortalNativeEngine
import org.gosuda.portal.internal.platformNativeEngine
import org.gosuda.portal.internal.SessionRegistry

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
    val droppedAggregateEvents: Long,
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
    private val allowRemoteTargets: Boolean = false,
    private val defaultIdentityPath: String? = null
) {
    /**
     * Creates a client backed by the platform `libportaltunnel` engine.
     * Tests inject a fake engine through the internal primary constructor.
     */
    public constructor(
        allowRemoteTargets: Boolean = false
    ) : this(platformNativeEngine(), allowRemoteTargets)

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Fire-and-forget cleanup must survive client close: a scope whose job is
    // NonCancellable is never cancelled, so orphan stops always run.
    private val cleanupScope = CoroutineScope(Dispatchers.Default + NonCancellable)

    private val closed = AtomicBoolean(false)
    private val closeMutex = Mutex()
    private val registry = SessionRegistry()
    private val _events = MutableSharedFlow<PortalEvent>(extraBufferCapacity = EVENT_BUFFER)
    private val generationCounter = AtomicLong(0)
    private val droppedAggregateEvents = AtomicLong(0)

    /**
     * Aggregated events from every session this client owns. Bounded and not
     * replayed; per-session state lives on [PortalTunnel.state].
     */
    public val events: SharedFlow<PortalEvent> = _events.asSharedFlow()

    /** Live sessions owned by this client, in open order. */
    public val sessions: StateFlow<List<PortalTunnel>> = registry.sessions

    /** True after [close] has been called. */
    public val isClosed: Boolean get() = closed.load()

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
        val resolved = config.copy(
            identityPath = config.identityPath ?: defaultIdentityPath,
            relays = config.relays?.map { ConfigValidation.normalizeRelayUrl(it) }
        )
        ConfigValidation.validate(resolved, allowRemoteTargets, capabilities())
        PortalEventHub.install(engine)

        val configJson = PortalJson.encodeToString(resolved)

        // The native start runs on the owner scope so a caller cancellation
        // cannot orphan a created handle: if the await is cancelled after the
        // native side started, the cleanup job stops it (design doc L.4).
        val startJob = cleanupScope.async(Dispatchers.Default) { engine.start(configJson) }
        val tunnelId = try {
            startJob.await()
        } catch (e: CancellationException) {
            cleanupScope.launch {
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
            config = resolved,
            owner = this,
            generation = (generationCounter.addAndFetch(1)).toInt()
        )
        // Register with the session registry first: draining buffered orphan
        // events may already mark the tunnel terminal (e.g. a STOPPED emitted
        // inside the native start), and markTerminal unregisters — a tunnel
        // registered only afterwards would linger as a zombie session.
        registry.register(tunnel)
        PortalEventHub.register(tunnel)

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
     * Starts a tunnel and returns it only after it reports ACTIVE — the v1
     * readiness signal covering every requested capability.
     *
     * If readiness fails or the call is cancelled, the session is stopped
     * before the outcome propagates, so a failed publish never leaks a live
     * tunnel. When cleanup itself fails, the session stays registered in
     * STOPPING (visible via [sessions]) and remains retryable through
     * [PortalTunnel.stop]; the cleanup failure is then reported with
     * `operation="publish_cleanup"` because an unreachable native session
     * needs operator attention. Cancellation always propagates as
     * `CancellationException`, even when cleanup also fails.
     *
     * Use [open] when the caller needs the accepted-before-ready semantics —
     * e.g. to observe CONNECTING or to await a single capability.
     */
    public suspend fun publish(
        config: PortalConfig,
        timeoutMillis: Long = 30_000
    ): PortalTunnel {
        val tunnel = open(config)
        try {
            tunnel.awaitActive(timeoutMillis)
            return tunnel
        } catch (e: CancellationException) {
            stopAfterFailedPublish(tunnel)
            throw e
        } catch (e: PortalException) {
            stopAfterFailedPublish(tunnel)?.let { cleanup ->
                throw PortalException(
                    cleanup.code,
                    "publish cleanup failed after readiness error " +
                        "${e.failure.code}: ${cleanup.failure.message}",
                    retryable = cleanup.failure.retryable,
                    operation = "publish_cleanup",
                    nativeCode = cleanup.failure.nativeCode
                )
            }
            throw e
        } catch (t: Throwable) {
            stopAfterFailedPublish(tunnel)
            throw t
        }
    }

    /**
     * Best-effort stop after a failed publish. Runs under [NonCancellable] so
     * caller cancellation cannot skip cleanup; a cancellation delivered to
     * the stop call itself still propagates. Returns the cleanup failure, or
     * null when the session was stopped (or was already terminal).
     */
    private suspend fun stopAfterFailedPublish(tunnel: PortalTunnel): PortalException? =
        withContext(NonCancellable) {
            try {
                tunnel.stop()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: PortalException) {
                e
            } catch (t: Throwable) {
                PortalException(
                    PortalFailure.Codes.INTERNAL_ERROR,
                    "publish cleanup failed: ${t.message}",
                    operation = "publish_cleanup"
                )
            }
        }

    public suspend fun close() {
        closeMutex.withLock {
            if (!closed.compareAndSet(false, true)) return
            try {
                val owned = registry.all()
                var firstFailure: PortalException? = null
                for (tunnel in owned) {
                    try {
                        tunnel.stop()
                    } catch (e: PortalException) {
                        if (firstFailure == null) firstFailure = e
                    }
                }
                firstFailure?.let { throw it }
            } finally {
                scope.cancel()
            }
        }
    }

    public fun diagnostics(): PortalDiagnostics {
        val sessions = registry.all().map {
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
            droppedAggregateEvents = droppedAggregateEvents.load(),
            sessions = sessions
        )
    }

    // ---- internal plumbing -------------------------------------------------

    internal val nativeEngine: PortalNativeEngine get() = engine

    /** Called by the hub after a tunnel reduced a raw event. */
    internal fun onTunnelEvent(event: PortalEvent) {
        if (!_events.tryEmit(event)) {
            droppedAggregateEvents.addAndFetch(1)
        }
    }

    internal fun unregisterTunnel(tunnelId: String) {
        registry.unregister(tunnelId)
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

/**
 * Entry point for constructing a [PortalClient] with named options.
 * Prefer this over the raw constructor for readability.
 */
public object Portal {
    /** Creates a client backed by the platform `libportaltunnel` engine. */
    public fun client(allowRemoteTargets: Boolean = false): PortalClient =
        PortalClient(allowRemoteTargets)

    /** Fluent builder for a client. */
    public fun builder(): PortalClientBuilder = PortalClientBuilder()
}

/** Fluent builder for [PortalClient]. */
public class PortalClientBuilder internal constructor() {
    private var allowRemoteTargets = false
    private var defaultIdentityPath: String? = null

    public fun allowRemoteTargets(value: Boolean): PortalClientBuilder =
        apply { allowRemoteTargets = value }

    public fun defaultIdentityPath(path: String): PortalClientBuilder =
        apply { defaultIdentityPath = path }

    public fun build(): PortalClient =
        PortalClient(platformNativeEngine(), allowRemoteTargets, defaultIdentityPath)
}


