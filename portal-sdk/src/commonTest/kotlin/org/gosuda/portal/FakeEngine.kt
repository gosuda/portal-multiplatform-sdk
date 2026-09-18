package org.gosuda.portal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gosuda.portal.internal.PortalJson
import org.gosuda.portal.internal.PortalNativeEngine

/**
 * In-memory [PortalNativeEngine] for lifecycle tests. Scripted behaviors let
 * tests reproduce the boundary conditions the design doc calls out: events
 * emitted inside `start` before the tunnel id is known, blocking starts for
 * cancellation tests, and failing stops.
 */
@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
internal class FakeEngine : PortalNativeEngine {

    var listener: ((String, String, String) -> Unit)? = null
    val stoppedIds = mutableListOf<String>()
    val startedIds = mutableListOf<String>()
    val startedConfigs = mutableListOf<PortalConfig>()

    /** When set, `start` suspends until this deferred completes. */
    var startGate: CompletableDeferred<Unit>? = null

    /** When set, `stop` throws this failure instead of succeeding. */
    var stopFailure: PortalException? = null

    /** When true, `start` emits STARTED + STATUS_CHANGED before returning. */
    var emitEventsInsideStart = true

    /** When true, `start` additionally emits STOPPED before returning. */
    var emitStoppedInsideStart = false

    var statusActive = true

    override fun setEventListener(listener: (String, String, String) -> Unit) {
        this.listener = listener
    }

    override fun generateIdentity(name: String): String =
        """{"name":"$name","address":"0xfake$name"}"""

    override fun parseIdentity(identityJson: String): String {
        if (!identityJson.contains("\"address\"")) {
            throw PortalException(PortalFailure.Codes.IDENTITY_INVALID, "bad identity")
        }
        return identityJson
    }

    override fun start(configJson: String): String {
        startGate?.let {
            if (!it.isCompleted) {
                // Blocking native start; tests complete the gate to release it.
                kotlinx.coroutines.runBlocking { it.await() }
            }
        }
        val config = PortalJson.decodeFromString<PortalConfig>(configJson)
        startedConfigs.add(config)
        val id = "fake-tunnel-${idCounter.addAndFetch(1)}"
        startedIds.add(id)
        if (emitEventsInsideStart) {
            emit(id, "STARTED", """{"name":"${config.name ?: ""}"}""")
            emit(id, "STATUS_CHANGED", statusJson(id, config))
        }
        if (emitStoppedInsideStart) {
            emit(id, "STOPPED", "{}")
        }
        return id
    }

    override fun stop(tunnelId: String) {
        stopFailure?.let { throw it }
        stoppedIds.add(tunnelId)
        emit(tunnelId, "STOPPED", "{}")
    }

    override fun stopAll() {
        stoppedIds.add("*")
    }

    override fun getStatus(tunnelId: String): String =
        statusJson(tunnelId, startedConfigs.lastOrNull())

    override fun addRelay(tunnelId: String, relayUrl: String) {}
    override fun removeRelay(tunnelId: String, relayUrl: String) {}
    override fun updateMetadata(tunnelId: String, metadataJson: String) {}

    fun emit(tunnelId: String, type: String, payload: String) {
        listener?.invoke(tunnelId, type, payload)
    }

    private fun statusJson(tunnelId: String, config: PortalConfig?): String {
        val name = config?.name ?: ""
        return PortalJson.encodeToString(
            PortalStatus(
                tunnelId = tunnelId,
                name = name,
                address = "0xfake",
                active = statusActive,
                publicUrls = if (statusActive) listOf("https://$name.portal.example") else emptyList(),
                relays = listOf(
                    PortalRelayStatus(
                        relayUrl = "https://relay.fake",
                        publicUrl = "https://$name.portal.example",
                        state = if (statusActive) "ready" else "connecting"
                    )
                )
            )
        )
    }
    private companion object {
        // Process-unique ids: PortalEventHub routes globally, so two fake
        // engines must never mint the same tunnel id.
        @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
        val idCounter = kotlin.concurrent.atomics.AtomicLong(0)
    }
}
