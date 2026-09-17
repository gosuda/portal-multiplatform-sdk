package org.gosuda.portal.internal

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.gosuda.portal.PortalClient
import org.gosuda.portal.PortalTunnel

/**
 * Process-global routing hub for native events.
 *
 * The v1 C ABI exposes a single process-wide event callback
 * (`PortalSetEventCallback` / the JNI `onNativeEvent` static). If every
 * [PortalClient] installed its own listener, the last client would steal
 * events from all others. The hub installs one listener per engine instance
 * and routes each event to the client that owns the tunnel id.
 *
 * Events for not-yet-registered tunnel ids are held in a bounded orphan
 * buffer and drained on registration — the v1 ABI can emit inside
 * `PortalStart`, before the id is known to Kotlin.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object PortalEventHub {

    private const val MAX_ORPHAN_SESSIONS = 32
    private const val MAX_ORPHAN_EVENTS_PER_SESSION = 64

    private val engines = AtomicReference<Set<PortalNativeEngine>>(emptySet())
    private val routes = AtomicReference<Map<String, PortalTunnel>>(emptyMap())
    private val orphans = AtomicReference<Map<String, List<RawNativeEvent>>>(emptyMap())
    private val droppedOrphans = AtomicLong(0)

    /** Events dropped because no route existed and the orphan buffer was full. */
    val droppedOrphanEvents: Long get() = droppedOrphans.load()

    /**
     * Installs the hub listener on [engine], once per engine instance.
     * Real platform engines share the process-global callback, so installing
     * on several of them is idempotent; test engines each get the listener so
     * every fake can deliver events.
     */
    fun install(engine: PortalNativeEngine) {
        while (true) {
            val current = engines.load()
            if (engine in current) return
            if (engines.compareAndSet(current, current + engine)) break
        }
        try {
            engine.setEventListener(::dispatch)
        } catch (t: Throwable) {
            engines.update { it - engine }
            throw t
        }
    }

    fun register(tunnel: PortalTunnel) {
        routes.update { it + (tunnel.tunnelId to tunnel) }
        drainOrphans(tunnel.tunnelId)
    }

    fun unregister(tunnelId: String) {
        routes.update { it - tunnelId }
        orphans.update { it - tunnelId }
    }

    private fun dispatch(tunnelId: String, eventType: String, payloadJson: String) {
        val tunnel = routes.load()[tunnelId]
        if (tunnel == null) {
            bufferOrphan(tunnelId, eventType, payloadJson)
            return
        }
        try {
            val event = tunnel.handleRawEvent(eventType, payloadJson)
            tunnel.owner.onTunnelEvent(event)
        } catch (t: Throwable) {
            // A reducer failure must never propagate into the native callback
            // thread; count it and move on.
            droppedOrphans.addAndFetch(1)
        }
    }

    private fun bufferOrphan(tunnelId: String, eventType: String, payloadJson: String) {
        while (true) {
            val current = orphans.load()
            val list = current[tunnelId]
            if (list == null && current.size >= MAX_ORPHAN_SESSIONS) {
                droppedOrphans.addAndFetch(1)
                return
            }
            val events = list.orEmpty()
            if (events.size >= MAX_ORPHAN_EVENTS_PER_SESSION) {
                droppedOrphans.addAndFetch(1)
                return
            }
            val next = current + (tunnelId to events + RawNativeEvent(eventType, payloadJson))
            if (orphans.compareAndSet(current, next)) return
        }
    }

    private fun drainOrphans(tunnelId: String) {
        val pending = orphans.load()[tunnelId] ?: return
        orphans.update { it - tunnelId }
        val tunnel = routes.load()[tunnelId]
        if (tunnel == null) {
            droppedOrphans.addAndFetch(pending.size.toLong())
            return
        }
        for (raw in pending) {
            try {
                val event = tunnel.handleRawEvent(raw.eventType, raw.payloadJson)
                tunnel.owner.onTunnelEvent(event)
            } catch (t: Throwable) {
                // Same contract as dispatch(): a reducer failure is counted,
                // never propagated into the caller (here: open()).
                droppedOrphans.addAndFetch(1)
            }
        }
    }

    private data class RawNativeEvent(val eventType: String, val payloadJson: String)
}

@OptIn(ExperimentalAtomicApi::class)
private inline fun <T> AtomicReference<T>.update(crossinline transform: (T) -> T) {
    while (true) {
        val current = load()
        if (compareAndSet(current, transform(current))) return
    }
}
