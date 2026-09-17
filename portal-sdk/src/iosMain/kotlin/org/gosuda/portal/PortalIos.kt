package org.gosuda.portal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Cancellation handle returned by the iOS-facing callback API. Cancelling an
 * observation never stops the tunnel; cancelling an operation asks the owner
 * to roll back.
 */
interface PortalSubscription {
    fun cancel()
}

/** Cancellation handle for an in-flight [PortalIosClient.open] call. */
interface PortalOperation {
    fun cancel()
}

/**
 * Callback-based session facade for Swift callers. Kotlin `Flow` and
 * `suspend` do not map to idiomatic Swift; this wrapper exposes the same
 * contract through completion handlers and explicit subscriptions.
 *
 * Completion callbacks are invoked on the main dispatcher. Each completion
 * fires exactly once. Observing and stopping are independent: cancelling an
 * observation leaves the tunnel running.
 */
class PortalIosSession internal constructor(
    private val tunnel: PortalTunnel,
    private val client: PortalIosClient
) {
    val sessionId: String get() = tunnel.tunnelId

    /** Current authoritative snapshot. */
    val snapshot: PortalSnapshot get() = tunnel.state.value

    /**
     * Observes state snapshots. The callback receives the current snapshot
     * immediately, then every revision. Returns a subscription that must be
     * cancelled when the observer goes away.
     */
    fun observeState(callback: (PortalSnapshot) -> Unit): PortalSubscription {
        val job = client.scope.launch(Dispatchers.Main) {
            tunnel.state.collect { callback(it) }
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
    fun observeEvents(callback: (PortalEvent) -> Unit): PortalSubscription {
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
    fun stop(completion: (PortalFailure?) -> Unit) {
        client.scope.launch(Dispatchers.Main) {
            val failure = try {
                tunnel.stop()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: PortalException) {
                e.failure
            } catch (t: Throwable) {
                PortalFailure(PortalFailure.Codes.INTERNAL_ERROR, t.message ?: "stop failed")
            }
            completion(failure)
        }
    }
}

/**
 * iOS-facing entry point. Wraps [PortalClient]; create once per app and keep
 * it alive for the tunnels' lifetime.
 */
class PortalIosClient(
    allowInsecureLocalRelays: Boolean = false,
    allowRemoteTargets: Boolean = false
) {
    private val client = PortalClient(allowInsecureLocalRelays, allowRemoteTargets)
    internal val scope get() = client.scope

    fun capabilities(): Set<Capability> = client.capabilities()

    /**
     * Starts a tunnel. Completion fires exactly once on the main dispatcher
     * with either the session or a failure. The returned operation cancels
     * the start; a session that already started is stopped during rollback.
     */
    fun open(
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
        return object : PortalOperation {
            override fun cancel() {
                job.cancel()
            }
        }
    }

    /** Stops all sessions owned by this client. */
    fun close(completion: (PortalFailure?) -> Unit) {
        scope.launch(Dispatchers.Main) {
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
    }
}
