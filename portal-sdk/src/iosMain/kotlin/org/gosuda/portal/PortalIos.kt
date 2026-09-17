package org.gosuda.portal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.gosuda.portal.internal.platformNativeEngine

/**
 * Cancellation handle returned by the iOS-facing callback API. Cancelling an
 * observation never stops the tunnel; cancelling an operation asks the owner
 * to roll back.
 */
public interface PortalSubscription {
    public fun cancel()
}

/** Cancellation handle for an in-flight [PortalIosClient.open] call. */
public interface PortalOperation {
    public fun cancel()
}

private val CLIENT_CLOSED_FAILURE =
    PortalFailure(PortalFailure.Codes.CLIENT_CLOSED, "client is closed")

/**
 * Callback-based session facade for Swift callers. Kotlin `Flow` and
 * `suspend` do not map to idiomatic Swift; this wrapper exposes the same
 * contract through completion handlers and explicit subscriptions.
 *
 * Completion callbacks are invoked on the main dispatcher. Each completion
 * fires exactly once — including after the owning client is closed, where it
 * reports [PortalFailure.Codes.CLIENT_CLOSED] instead of never firing.
 * Observing and stopping are independent: cancelling an observation leaves
 * the tunnel running.
 */
public class PortalIosSession internal constructor(
    private val tunnel: PortalTunnel,
    private val client: PortalIosClient
) {
    public val sessionId: String get() = tunnel.tunnelId

    /** Current authoritative snapshot. */
    public val snapshot: PortalSnapshot get() = tunnel.state.value

    /** Tunnel name from the latest native status, or the configured name. */
    public val name: String? get() = tunnel.name

    /** Identity address from the latest native status, if reported. */
    public val address: String? get() = tunnel.address

    /** True while the session is in [TunnelPhase.ACTIVE]. */
    public val isActive: Boolean get() = tunnel.isActive

    /**
     * Observes state snapshots. The callback receives the current snapshot
     * immediately, then every revision. Returns a subscription that must be
     * cancelled when the observer goes away.
     */
    public fun observeState(callback: (PortalSnapshot) -> Unit): PortalSubscription {
        val job = client.scope.launch(Dispatchers.Main) {
            tunnel.state.collect { callback(it) }
        }
        if (job.isCancelled) {
            // The client is closed and the scope is dead; still deliver the
            // current (terminal) snapshot once so observers settle.
            callback(tunnel.state.value)
        }
        return object : PortalSubscription {
            override fun cancel() {
                job.cancel()
            }
        }
    }

    /**
     * Observes auxiliary events. Durable state is on [observeState]; events
     * are bounded and not replayed.
     */
    public fun observeEvents(callback: (PortalEvent) -> Unit): PortalSubscription {
        val job = client.scope.launch(Dispatchers.Main) {
            tunnel.events.collect { callback(it) }
        }
        return object : PortalSubscription {
            override fun cancel() {
                job.cancel()
            }
        }
    }

    /** Stops the session; completion receives null on success. */
    public fun stop(completion: (PortalFailure?) -> Unit) {
        enqueue(completion) {
            try {
                tunnel.stop()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: PortalException) {
                e.failure
            } catch (t: Throwable) {
                PortalFailure(PortalFailure.Codes.INTERNAL_ERROR, t.message ?: "stop failed")
            }
        }
    }

    /** Fetches the authoritative native status into the snapshot. */
    public fun refresh(completion: (PortalFailure?) -> Unit) {
        enqueue(completion) { runSessionOp("refresh") { tunnel.refresh() } }
    }

    /** Adds one relay without restarting the session. */
    public fun addRelay(relayUrl: String, completion: (PortalFailure?) -> Unit) {
        enqueue(completion) { runSessionOp("addRelay") { tunnel.addRelay(relayUrl) } }
    }

    /** Removes one relay without restarting the session. */
    public fun removeRelay(relayUrl: String, completion: (PortalFailure?) -> Unit) {
        enqueue(completion) { runSessionOp("removeRelay") { tunnel.removeRelay(relayUrl) } }
    }

    /** Updates public metadata without restarting the session. */
    public fun updateMetadata(metadata: PortalMetadata, completion: (PortalFailure?) -> Unit) {
        enqueue(completion) { runSessionOp("updateMetadata") { tunnel.updateMetadata(metadata) } }
    }

    /**
     * Suspends until [capability] is ready, then completes with the snapshot.
     * Failure codes mirror [PortalTunnel.awaitReady].
     */
    public fun awaitReady(
        capability: Capability,
        timeoutMillis: Long = 30_000,
        completion: (PortalSnapshot?, PortalFailure?) -> Unit
    ) {
        enqueueSnapshot(completion) { tunnel.awaitReady(capability, timeoutMillis) }
    }

    /**
     * Suspends until the session reports ACTIVE, then completes with the
     * snapshot. Failure codes mirror [PortalTunnel.awaitActive].
     */
    public fun awaitActive(
        timeoutMillis: Long = 30_000,
        completion: (PortalSnapshot?, PortalFailure?) -> Unit
    ) {
        enqueueSnapshot(completion) { tunnel.awaitActive(timeoutMillis) }
    }

    /**
     * Runs [block] on the main dispatcher and delivers its result to
     * [completion]. If the client scope is already cancelled, the job never
     * runs — report CLIENT_CLOSED so the completion still fires exactly once.
     */
    private fun enqueue(completion: (PortalFailure?) -> Unit, block: suspend () -> PortalFailure?) {
        val job = client.scope.launch(Dispatchers.Main) { completion(block()) }
        if (job.isCancelled) completion(CLIENT_CLOSED_FAILURE)
    }

    private fun enqueueSnapshot(
        completion: (PortalSnapshot?, PortalFailure?) -> Unit,
        block: suspend () -> PortalSnapshot
    ) {
        val job = client.scope.launch(Dispatchers.Main) {
            try {
                completion(block(), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PortalException) {
                completion(null, e.failure)
            } catch (t: Throwable) {
                completion(
                    null,
                    PortalFailure(PortalFailure.Codes.INTERNAL_ERROR, t.message ?: "operation failed")
                )
            }
        }
        if (job.isCancelled) completion(null, CLIENT_CLOSED_FAILURE)
    }

    private suspend fun runSessionOp(name: String, block: suspend () -> Any?): PortalFailure? =
        try {
            block()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: PortalException) {
            e.failure
        } catch (t: Throwable) {
            PortalFailure(PortalFailure.Codes.INTERNAL_ERROR, t.message ?: "$name failed")
        }
}

/**
 * iOS-facing entry point. Wraps [PortalClient]; create once per app and keep
 * it alive for the tunnels' lifetime.
 *
 * @param allowRemoteTargets permits non-loopback `target_addr`/`udp_addr`.
 * @param defaultIdentityPath fallback `identity_path` for configs that set
 *   neither `identity_json` nor `identity_path`.
 */
public class PortalIosClient(
    allowRemoteTargets: Boolean = false,
    defaultIdentityPath: String? = null
) {
    private val client = PortalClient(platformNativeEngine(), allowRemoteTargets, defaultIdentityPath)
    internal val scope get() = client.scope

    /** True after [close] has been called. */
    public val isClosed: Boolean get() = client.isClosed

    public fun capabilities(): Set<Capability> = client.capabilities()

    /** Read-only diagnostic snapshot; never contains secrets. */
    public fun diagnostics(): PortalDiagnostics = client.diagnostics()

    /**
     * Starts a tunnel. Completion fires exactly once on the main dispatcher
     * with either the session or a failure. The returned operation cancels
     * the start; a session that already started is stopped during rollback.
     * Cancelling the operation means the completion does not fire.
     */
    public fun open(
        config: PortalConfig,
        completion: (PortalIosSession?, PortalFailure?) -> Unit
    ): PortalOperation {
        val job = scope.launch(Dispatchers.Main) {
            try {
                val tunnel = client.open(config)
                completion(PortalIosSession(tunnel, this@PortalIosClient), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PortalException) {
                completion(null, e.failure)
            } catch (t: Throwable) {
                completion(
                    null,
                    PortalFailure(PortalFailure.Codes.INTERNAL_ERROR, t.message ?: "open failed")
                )
            }
        }
        if (job.isCancelled) completion(null, CLIENT_CLOSED_FAILURE)
        return object : PortalOperation {
            override fun cancel() {
                job.cancel()
            }
        }
    }

    /**
     * Stops all sessions owned by this client. Completion receives null on
     * success; after the client is closed it reports CLIENT_CLOSED.
     */
    public fun close(completion: (PortalFailure?) -> Unit) {
        val job = scope.launch(Dispatchers.Main) {
            val failure = try {
                client.close()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: PortalException) {
                e.failure
            } catch (t: Throwable) {
                PortalFailure(PortalFailure.Codes.INTERNAL_ERROR, t.message ?: "close failed")
            }
            completion(failure)
        }
        if (job.isCancelled) completion(CLIENT_CLOSED_FAILURE)
    }
}
