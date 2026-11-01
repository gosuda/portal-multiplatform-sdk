package org.gosuda.portal

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    /** Version of the bundled `libportaltunnel` engine (portal-tunnel core). */
    val engineVersion: String,
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
        val droppedEvents: Long,
        /** Relay currently serving the session, if the engine reported one. */
        val activeRelay: String? = null,
        /** Last structured failure on the session; never contains secrets. */
        val lastFailure: PortalFailure? = null
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
    private val defaultIdentityPath: (() -> String?)? = null
) {
    /**
     * Creates a client backed by the platform `libportaltunnel` engine.
     * Tests inject a fake engine through the internal primary constructor.
     */
    public constructor(
        allowRemoteTargets: Boolean = false
    ) : this(platformNativeEngine(), allowRemoteTargets)

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Native start and rollback must survive cancellation of both the caller
    // and the client scope until every created handle has an owner.
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
            identityPath = config.identityPath?.takeIf { it.isNotEmpty() } ?: if (config.identityJson.isNullOrEmpty()) {
                defaultIdentityPath?.invoke()
            } else null,
            relays = config.relays?.map { ConfigValidation.normalizeRelayUrl(it) }
        )
        ConfigValidation.validate(resolved, allowRemoteTargets, capabilities())
        PortalEventHub.install(engine)

        val configJson = PortalJson.encodeToString(resolved)

        // Starting and adopting the handle share an uncancelled owner job.
        // Even cancellation while awaiting registration must retain ownership
        // until stop succeeds, so failed rollback remains retryable.
        val startJob = cleanupScope.async(Dispatchers.Default) {
            val tunnel = PortalTunnel(
                tunnelId = engine.start(configJson),
                config = resolved,
                owner = this@PortalClient,
                generation = generationCounter.addAndFetch(1).toInt()
            )
            closeMutex.withLock {
                // Adopt even after close: the late handle must remain visible
                // if its cleanup fails. open immediately rolls it back below.
                registry.register(tunnel)
                PortalEventHub.register(tunnel)
            }
            tunnel
        }
        val tunnel = try {
            startJob.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                runCatching { startJob.await() }.getOrNull()?.let { rollbackStart(it) }
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
            rollbackStart(tunnel)
            throw PortalException(PortalFailure.Codes.CLIENT_CLOSED, "client is closed")
        }

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
            val readiness = e.failure.copy(operation = e.failure.operation ?: "publish")
            stopAfterFailedPublish(tunnel)?.let { cleanup ->
                throw PortalException(
                    cleanup.failure.copy(
                        message = "publish cleanup failed after readiness error " +
                            "${readiness.code}: ${cleanup.failure.message}",
                        operation = "publish_cleanup",
                        readinessFailure = readiness
                    )
                )
            }
            throw PortalException(readiness)
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
        withContext(NonCancellable) {
            closeMutex.withLock {
                closed.store(true)
                try {
                    var firstFailure: PortalException? = null
                    for (tunnel in registry.all()) {
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
    }

    public fun diagnostics(): PortalDiagnostics {
        val sessions = registry.all().map {
            val s = it.state.value
            PortalDiagnostics.SessionDiagnostics(
                sessionId = it.tunnelId,
                generation = it.generation,
                phase = s.phase,
                revision = s.revision,
                droppedEvents = s.droppedEventCount,
                activeRelay = s.relays.firstOrNull { r -> r.isActive }?.relayUrl?.let { relay ->
                    // Diagnostic exports need the serving relay's origin, not
                    // URL paths, query tokens, fragments or credentials.
                    runCatching { ConfigValidation.normalizeRelayUrl(relay) }.getOrNull()?.let { normalized ->
                        val pathStart = normalized.indexOf('/', normalized.indexOf("://") + 3)
                        if (pathStart < 0) normalized else normalized.substring(0, pathStart)
                    }
                },
                lastFailure = s.lastFailure?.forDiagnostics(it.config)
            )
        }
        return PortalDiagnostics(
            sdkVersion = SDK_VERSION,
            engineVersion = ENGINE_VERSION,
            abiVersion = ABI_VERSION,
            wireSchemaVersion = WIRE_SCHEMA_VERSION,
            activeSessions = sessions.size,
            droppedOrphanEvents = PortalEventHub.droppedOrphanEvents,
            droppedAggregateEvents = droppedAggregateEvents.load(),
            sessions = sessions
        )
    }

    private fun PortalFailure.forDiagnostics(config: PortalConfig): PortalFailure {
        fun redact(text: String): String {
            var result = text
            fun removeSecret(secret: String?) {
                if (!secret.isNullOrEmpty()) result = result.replace(secret, "<redacted>")
            }
            removeSecret(config.identityJson)
            // Native parser errors can quote only a private field rather than
            // the complete document. Preserve public identity name/address.
            fun removeIdentityMaterial(element: JsonElement) {
                when (element) {
                    is JsonObject -> element.forEach { (key, value) ->
                        if (key != "name" && key != "address") removeIdentityMaterial(value)
                    }
                    is JsonArray -> element.forEach(::removeIdentityMaterial)
                    is JsonPrimitive -> if (element.isString) removeSecret(element.content)
                }
            }
            config.identityJson?.let { document ->
                runCatching { PortalJson.parseToJsonElement(document) }.getOrNull()
                    ?.let(::removeIdentityMaterial)
            }
            return result
        }
        return copy(
            message = redact(message),
            readinessFailure = readinessFailure?.forDiagnostics(config)
        )
    }

    // ---- internal plumbing -------------------------------------------------

    /** Called by the hub after a tunnel reduced a raw event. */
    internal fun onTunnelEvent(event: PortalEvent) {
        if (_events.subscriptionCount.value == 0 || !_events.tryEmit(event)) {
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
                tunnel.stop()
            } catch (_: PortalException) {
                // stop retains a failed cleanup in STOPPING for owner retry.
            }
        }
    }

    private companion object {
        const val EVENT_BUFFER = 128

        const val SDK_VERSION = "0.1.0"

        /** portal-tunnel core version the bundled `native/bridge` builds on. */
        const val ENGINE_VERSION = "2.7.1"
        const val ABI_VERSION = 1
        const val WIRE_SCHEMA_VERSION = 1

        val SUPPORTED_CAPABILITIES: Set<Capability> = setOf(
            Capability.HTTP_TLS,
            Capability.STATIC_SITE,
            Capability.TCP,
            Capability.UDP,
            Capability.DISCOVERY
            // ECH, X402 and OVERLAY are not supported by the bundled bridge.
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
        PortalClient(platformNativeEngine(), allowRemoteTargets, defaultIdentityPath?.let { p -> { p } })
}

/**
 * Runs [block] with this client and closes it afterwards — the managed
 * lifetime pattern for JVM/desktop and common callers. [close] stops only
 * the sessions this client owns; a close failure propagates after [block]'s
 * own outcome, never silently swallowing it.
 */
public suspend fun <T> PortalClient.use(block: suspend (PortalClient) -> T): T {
    var failure: Throwable? = null
    try {
        return block(this)
    } catch (t: Throwable) {
        failure = t
        throw t
    } finally {
        try {
            close()
        } catch (e: PortalException) {
            failure?.addSuppressed(e) ?: throw e
        }
    }
}


