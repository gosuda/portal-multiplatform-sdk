package org.gosuda.portal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class PortalClientTest {

    private fun clientWith(engine: FakeEngine) = PortalClient(engine = engine)

    private fun siteConfig(name: String = "site") = PortalConfig(
        name = name,
        staticDir = "/data/site",
        staticIndex = "index.html",
        relays = listOf("https://relay.example"),
        discovery = false
    )

    @Test
    fun openDeliversEventsEmittedInsideNativeStart() = runTest {
        // The v1 ABI emits STARTED/STATUS_CHANGED inside start(), before the
        // tunnel id is known to Kotlin. The orphan buffer must preserve them.
        val engine = FakeEngine()
        val client = clientWith(engine)

        val tunnel = client.open(siteConfig())

        val snapshot = tunnel.state.value
        assertEquals(TunnelPhase.ACTIVE, snapshot.phase)
        assertEquals(listOf("https://site.portal.example"), snapshot.publicUrls)
        assertTrue(Capability.STATIC_SITE in snapshot.readyCapabilities)
        assertTrue(snapshot.revision > 0)
        client.close()
    }

    @Test
    fun stopTransitionsToTerminalAndUnregisters() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        val tunnel = client.open(siteConfig())

        tunnel.stop()

        assertEquals(TunnelPhase.STOPPED, tunnel.state.value.phase)
        assertTrue(tunnel.state.value.isTerminal)
        assertEquals(listOf(tunnel.tunnelId), engine.stoppedIds)
        assertEquals(0, client.diagnostics().activeSessions)
        client.close()
    }

    @Test
    fun concurrentStopsStopNativeOnce() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        val tunnel = client.open(siteConfig())

        val jobs = List(10) { async { tunnel.stop() } }
        jobs.forEach { it.await() }

        assertEquals(1, engine.stoppedIds.count { it == tunnel.tunnelId })
        assertEquals(TunnelPhase.STOPPED, tunnel.state.value.phase)
        client.close()
    }

    @Test
    fun failedStopKeepsHandleRegisteredAndRetryable() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        val tunnel = client.open(siteConfig())
        engine.stopFailure = PortalException(PortalFailure.Codes.INTERNAL_ERROR, "boom")

        assertFailsWith<PortalException> { tunnel.stop() }
        assertEquals(TunnelPhase.STOPPING, tunnel.state.value.phase)
        assertNotNull(tunnel.state.value.lastFailure)
        assertEquals(1, client.diagnostics().activeSessions)

        engine.stopFailure = null
        tunnel.stop()
        assertEquals(TunnelPhase.STOPPED, tunnel.state.value.phase)
        client.close()
    }

    @Test
    fun cancelDuringOpenRollsBackNativeHandle() = runTest {
        val engine = FakeEngine()
        engine.emitEventsInsideStart = false
        engine.startGate = CompletableDeferred()
        val client = clientWith(engine)

        val openJob = async { client.open(siteConfig()) }
        yield()
        openJob.cancel()
        engine.startGate!!.complete(Unit)

        assertFailsWith<kotlinx.coroutines.CancellationException> { openJob.await() }
        // The cleanup job runs on the owner scope; poll until it lands.
        var spins = 0
        while (engine.stoppedIds.isEmpty() && spins < 10_000) {
            yield()
            spins++
        }
        assertTrue(engine.stoppedIds.isNotEmpty(), "orphan native handle was not stopped")
        client.close()
    }

    @Test
    fun lateEventsFromStoppedSessionDoNotResurrect() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        val tunnel = client.open(siteConfig())
        tunnel.stop()

        engine.emit(tunnel.tunnelId, "STATUS_CHANGED", engine.getStatus(tunnel.tunnelId))

        assertEquals(TunnelPhase.STOPPED, tunnel.state.value.phase)
        client.close()
    }

    @Test
    fun mitmEventSetsStickySecurityWarning() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        val tunnel = client.open(siteConfig())

        engine.emit(tunnel.tunnelId, "MITM_SUSPECTED", """{"relay_url":"https://evil.example"}""")

        assertTrue(tunnel.state.value.hasSecurityWarning)
        client.close()
    }

    @Test
    fun eventsWithoutSubscribersAreCountedAsDropped() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        val tunnel = client.open(siteConfig())

        engine.emit(tunnel.tunnelId, "ERROR", """{"error":"x"}""")

        assertTrue(tunnel.state.value.droppedEventCount > 0)
        client.close()
    }

    @Test
    fun subscribedEventsAreDelivered() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        val tunnel = client.open(siteConfig())

        val received = CompletableDeferred<PortalEvent>()
        val job = launch { received.complete(tunnel.events.first()) }
        yield()
        engine.emit(tunnel.tunnelId, "ERROR", """{"error":"relay down"}""")

        val event = received.await()
        kotlin.test.assertIs<PortalEvent.Error>(event)
        assertEquals("relay down", event.message)
        client.close()
    }

    @Test
    fun eventsRouteToOwningClientWhenSeveralClientsExist() = runTest {
        // The v1 ABI has one process-global callback; the hub must route by
        // tunnel id so a second client cannot steal the first client's events.
        // One shared engine simulates that single global listener slot.
        val engine = FakeEngine()
        val clientA = clientWith(engine)
        val clientB = clientWith(engine)
        val tunnelA = clientA.open(siteConfig("a"))
        clientB.open(siteConfig("b"))

        val received = CompletableDeferred<PortalEvent>()
        val job = launch { received.complete(clientA.events.first()) }
        yield()
        engine.emit(tunnelA.tunnelId, "ERROR", """{"error":"a-relay down"}""")

        val event = received.await()
        kotlin.test.assertIs<PortalEvent.Error>(event)
        assertEquals(tunnelA.tunnelId, event.tunnelId)
        clientA.close()
        clientB.close()
    }


    @Test
    fun closeStopsOnlyOwnedSessions() = runTest {
        val engineA = FakeEngine()
        val engineB = FakeEngine()
        val clientA = clientWith(engineA)
        val clientB = clientWith(engineB)
        val tunnelA = clientA.open(siteConfig("a"))
        val tunnelB = clientB.open(siteConfig("b"))

        clientA.close()

        assertEquals(TunnelPhase.STOPPED, tunnelA.state.value.phase)
        assertFalse(tunnelB.state.value.isTerminal)
        assertFailsWith<PortalException> { clientA.open(siteConfig("again")) }
        clientB.close()
    }

    @Test
    fun awaitReadyResolvesOnActive() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        val tunnel = client.open(siteConfig())

        val snapshot = tunnel.awaitReady(Capability.STATIC_SITE, timeoutMillis = 5_000)
        assertEquals(TunnelPhase.ACTIVE, snapshot.phase)
        client.close()
    }

    @Test
    fun awaitReadyRejectsUnrequestedCapability() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        val tunnel = client.open(siteConfig())

        val e = assertFailsWith<PortalException> {
            tunnel.awaitReady(Capability.TCP, timeoutMillis = 100)
        }
        assertEquals(PortalFailure.Codes.UNSUPPORTED_CAPABILITY, e.code)
        client.close()
    }

    @Test
    fun validationRejectsBadConfigs() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)

        // discovery off without relays
        assertFailsWith<PortalException> {
            client.open(PortalConfig(discovery = false))
        }
        // both identity inputs
        assertFailsWith<PortalException> {
            client.open(siteConfig().copy(identityJson = "x", identityPath = "/p"))
        }
        // insecure remote relay (upstream allows http only for loopback)
        assertFailsWith<PortalException> {
            client.open(siteConfig().copy(relays = listOf("http://relay.example")))
        }
        // ws/wss are not valid relay schemes upstream
        assertFailsWith<PortalException> {
            client.open(siteConfig().copy(relays = listOf("wss://relay.example")))
        }
        // local http relay is accepted (engine upgrades to https)
        client.open(siteConfig().copy(relays = listOf("http://localhost:8080"))).stop()
        // bare host defaults to https
        client.open(siteConfig().copy(relays = listOf("relay.example"))).stop()
        // path escape
        assertFailsWith<PortalException> {
            client.open(siteConfig().copy(staticDir = "/data/../secrets"))
        }
        // remote target without opt-in
        assertFailsWith<PortalException> {
            client.open(siteConfig().copy(targetAddr = "10.0.0.5:8080"))
        }
        // route with both upstream and static root
        assertFailsWith<PortalException> {
            client.open(
                siteConfig().copy(
                    httpRoutes = listOf(
                        PortalHTTPRoute(prefix = "/x", upstream = "http://a", staticRoot = "/b")
                    )
                )
            )
        }
        // overlay is not in the v1 capability set
        val e = assertFailsWith<PortalException> {
            client.open(siteConfig().copy(overlay = true))
        }
        assertEquals(PortalFailure.Codes.UNSUPPORTED_CAPABILITY, e.code)
        client.close()
    }

    @Test
    fun identityRoundTripAndRedaction() {
        val engine = FakeEngine()
        val identity = PortalIdentity.generate(engine, "alice")
        assertEquals("alice", identity.name)
        assertEquals("0xfakealice", identity.address)
        assertFalse(identity.toString().contains(identity.document))

        val parsed = PortalIdentity.parse(engine, identity.document)
        assertEquals(identity.address, parsed.address)

        assertFailsWith<PortalException> {
            PortalIdentity.parse(engine, """{"name":"no-address"}""")
        }
    }
}
