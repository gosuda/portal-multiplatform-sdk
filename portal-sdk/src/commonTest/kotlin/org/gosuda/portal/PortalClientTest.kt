package org.gosuda.portal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import org.gosuda.portal.internal.PortalJson

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
    fun cancelledOpenKeepsFailedRollbackOwnedAndRetryable() = runTest {
        val engine = FakeEngine().apply {
            statusFailure = CancellationException("cancelled during native status")
            stopFailure = PortalException(PortalFailure.Codes.INTERNAL_ERROR, "stop failed")
        }
        val client = clientWith(engine)

        assertFailsWith<CancellationException> { client.open(siteConfig()) }
        val tunnel = client.sessions.value.single()
        assertEquals(TunnelPhase.STOPPING, tunnel.state.value.phase)
        assertEquals(PortalFailure.Codes.INTERNAL_ERROR, tunnel.state.value.lastFailure?.code)

        engine.stopFailure = null
        client.close()
        assertEquals(TunnelPhase.STOPPED, tunnel.state.value.phase)
        assertTrue(client.sessions.value.isEmpty())
    }

    @Test
    fun closeRetriesSessionsWhoseNativeStopFailed() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        val tunnel = client.open(siteConfig())
        engine.stopFailure = PortalException(PortalFailure.Codes.INTERNAL_ERROR, "stop failed")

        assertFailsWith<PortalException> { client.close() }
        assertTrue(client.isClosed)
        assertEquals(listOf(tunnel), client.sessions.value)

        engine.stopFailure = null
        client.close()
        assertEquals(TunnelPhase.STOPPED, tunnel.state.value.phase)
        assertTrue(client.sessions.value.isEmpty())
    }

    @Test
    fun cancellingManagedLifetimeStillClosesOwnedSessions() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        val opened = CompletableDeferred<PortalTunnel>()
        val job = launch {
            client.use {
                opened.complete(it.open(siteConfig()))
                awaitCancellation()
            }
        }
        val tunnel = opened.await()

        job.cancelAndJoin()

        assertEquals(TunnelPhase.STOPPED, tunnel.state.value.phase)
        assertTrue(client.sessions.value.isEmpty())
    }


    @Test
    fun cancelDuringOpenRollsBackNativeHandle() = runTest {
        val engine = FakeEngine()
        engine.emitEventsInsideStart = false
        engine.startGate = CompletableDeferred()
        engine.startEntered = CompletableDeferred()
        val client = clientWith(engine)

        val openJob = async { client.open(siteConfig()) }
        engine.startEntered!!.await()
        openJob.cancel()
        engine.startGate!!.complete(Unit)

        assertFailsWith<kotlinx.coroutines.CancellationException> { openJob.await() }
        assertEquals(engine.startedIds.toList(), engine.stoppedIds.toList())
        assertTrue(client.sessions.value.isEmpty())
        client.close()
    }

    @Test
    fun closeDuringNativeStartRejectsAndStopsLateHandle() = runTest {
        val engine = FakeEngine().apply {
            emitEventsInsideStart = false
            startEntered = CompletableDeferred()
            startGate = CompletableDeferred()
        }
        val client = clientWith(engine)

        val openResult = async { runCatching { client.open(siteConfig()) } }
        engine.startEntered!!.await()
        client.close()
        engine.startGate!!.complete(Unit)

        val failure = openResult.await().exceptionOrNull() as? PortalException
        assertNotNull(failure)
        assertEquals(PortalFailure.Codes.CLIENT_CLOSED, failure.code)
        assertEquals(engine.startedIds.toList(), engine.stoppedIds.toList())
        assertTrue(client.sessions.value.isEmpty())
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
        val sessionDrops = tunnel.state.value.droppedEventCount
        val clientDrops = client.diagnostics().droppedAggregateEvents

        engine.emit(tunnel.tunnelId, "ERROR", """{"error":"x"}""")

        assertEquals(sessionDrops + 1, tunnel.state.value.droppedEventCount)
        assertEquals(clientDrops + 1, client.diagnostics().droppedAggregateEvents)
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
        // Factory output reaches the same validation path.
        assertFailsWith<PortalException> {
            client.open(PortalConfig.http("10.0.0.5:8080"))
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
    fun sessionsFlowTracksLiveTunnels() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)

        assertTrue(client.sessions.value.isEmpty())
        val a = client.open(siteConfig("a"))
        val b = client.open(siteConfig("b"))
        assertEquals(listOf(a.tunnelId, b.tunnelId), client.sessions.value.map { it.tunnelId })

        a.stop()
        assertEquals(listOf(b.tunnelId), client.sessions.value.map { it.tunnelId })
        client.close()
        assertTrue(client.sessions.value.isEmpty())
    }

    @Test
    fun awaitActiveTimesOut() = runTest {
        val engine = FakeEngine().apply { statusActive = false }
        val client = clientWith(engine)
        val tunnel = client.open(siteConfig())

        val e = assertFailsWith<PortalException> {
            tunnel.awaitActive(timeoutMillis = 50)
        }
        assertEquals(PortalFailure.Codes.STOP_TIMEOUT, e.code)
        client.close()
    }

    @Test
    fun outerReadinessTimeoutRemainsCancellation() = runTest {
        val client = clientWith(FakeEngine().apply { statusActive = false })
        val tunnel = client.open(siteConfig())
        try {
            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                withTimeout(1) { tunnel.awaitActive(timeoutMillis = 30_000) }
            }
            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                withTimeout(1) { tunnel.awaitReady(Capability.STATIC_SITE, timeoutMillis = 30_000) }
            }
            assertFalse(tunnel.state.value.isTerminal)
        } finally {
            client.close()
        }
    }

    @Test
    fun isClosedReflectsClose() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        assertFalse(client.isClosed)
        client.close()
        assertTrue(client.isClosed)
        assertFailsWith<PortalException> { client.open(siteConfig()) }
    }

    @Test
    fun relayUrlsAreNormalizedBeforeStart() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        client.open(siteConfig().copy(relays = listOf("relay.example", " https://r2.example ")))
        val sent = engine.startedConfigs.last()
        assertEquals(listOf("https://relay.example", "https://r2.example"), sent.relays)
        client.close()
    }

    @Test
    fun relayEditsCannotBypassValidationAndUseTheSameCanonicalAddress() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        try {
            val tunnel = client.open(siteConfig())
            val failure = assertFailsWith<PortalException> {
                tunnel.addRelay("http://remote.example")
            }
            assertEquals(PortalFailure.Codes.INVALID_CONFIG, failure.code)
            assertTrue(engine.addedRelays.isEmpty())
            tunnel.addRelay(" http://[::1]:8443/relay?token=discarded ")
            tunnel.removeRelay("http://[::1]:8443/relay")
            assertEquals(listOf("https://[::1]:8443"), engine.addedRelays)
            assertEquals(engine.addedRelays, engine.removedRelays)
        } finally {
            client.close()
        }
    }

    @Test
    fun explicitIdentitySkipsDefaultPathResolution() = runTest {
        val client = PortalClient(
            engine = FakeEngine(),
            defaultIdentityPath = { error("default identity directory is unavailable") }
        )
        try {
            val tunnel = client.open(siteConfig().copy(identityJson = """{"address":"0xexplicit"}"""))
            assertEquals(TunnelPhase.ACTIVE, tunnel.state.value.phase)
        } finally {
            client.close()
        }
    }


    @Test
    fun publishReturnsActiveTunnel() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)

        val tunnel = client.publish(siteConfig())

        assertEquals(TunnelPhase.ACTIVE, tunnel.state.value.phase)
        assertEquals("https://site.portal.example", tunnel.state.value.primaryPublicUrl)
        assertEquals(listOf(tunnel.tunnelId), client.sessions.value.map { it.tunnelId })
        client.close()
    }

    @Test
    fun rawTransportReadinessRequiresItsOwnEndpointAndTracksWithdrawal() = runTest {
        val engine = FakeEngine().apply { statusActive = false }
        val client = clientWith(engine)
        try {
            val tunnel = client.open(PortalConfig(
                targetAddr = "127.0.0.1:8080", tcp = true,
                udp = true, udpAddr = "127.0.0.1:8081",
                discovery = false, relays = listOf("https://relay.example")
            ))
            fun emit(tcp: String? = null, udp: String? = null) {
                engine.emit(tunnel.tunnelId, "STATUS_CHANGED", PortalJson.encodeToString(PortalStatus(
                    tunnelId = tunnel.tunnelId, name = "transports", address = "0xfake",
                    active = true, publicUrls = listOf("https://service.example"),
                    relays = listOf(PortalRelayStatus(
                        relayUrl = "https://relay.example", state = "ready",
                        publicUrl = "https://service.example", tcpAddr = tcp, udpAddr = udp
                    ))
                )))
            }

            emit()
            assertEquals(TunnelPhase.CONNECTING, tunnel.state.value.phase)
            assertTrue(Capability.HTTP_TLS in tunnel.state.value.readyCapabilities)
            assertEquals(PortalFailure.Codes.STOP_TIMEOUT,
                assertFailsWith<PortalException> { tunnel.awaitReady(Capability.UDP, 1) }.code)

            emit(tcp = "tcp.example:4000")
            assertTrue(Capability.TCP in tunnel.awaitReady(Capability.TCP, 1).readyCapabilities)
            assertEquals(PortalFailure.Codes.STOP_TIMEOUT,
                assertFailsWith<PortalException> { tunnel.awaitActive(1) }.code)

            emit(tcp = "tcp.example:4000", udp = "udp.example:4001")
            assertEquals(TunnelPhase.ACTIVE, tunnel.awaitActive(1).phase)
            assertTrue(Capability.UDP in tunnel.awaitReady(Capability.UDP, 1).readyCapabilities)

            emit(tcp = "tcp.example:4000")
            assertEquals(TunnelPhase.CONNECTING, tunnel.state.value.phase)
            assertFalse(Capability.UDP in tunnel.state.value.readyCapabilities)
            assertTrue(Capability.TCP in tunnel.state.value.readyCapabilities)
        } finally {
            client.close()
        }
    }

    @Test
    fun publishWaitsForDelayedActive() = runTest {
        val engine = FakeEngine().apply { statusActive = false }
        val client = clientWith(engine)

        val publishJob = async { client.publish(siteConfig()) }
        awaitStarted(engine)

        engine.statusActive = true
        engine.emit(engine.startedIds.last(), "STATUS_CHANGED", engine.getStatus(engine.startedIds.last()))

        val tunnel = publishJob.await()
        assertEquals(TunnelPhase.ACTIVE, tunnel.state.value.phase)
        client.close()
    }

    @Test
    fun publishTimeoutStopsSession() = runTest {
        val engine = FakeEngine().apply { statusActive = false }
        val client = clientWith(engine)

        val e = assertFailsWith<PortalException> {
            client.publish(siteConfig(), timeoutMillis = 50)
        }
        assertEquals(PortalFailure.Codes.STOP_TIMEOUT, e.code)
        assertEquals(engine.startedIds, engine.stoppedIds)
        assertTrue(client.sessions.value.isEmpty())
        client.close()
    }

    @Test
    fun publishTerminalSessionDoesNotResurrect() = runTest {
        // STOPPED arrives inside the native start (orphan buffer path), so by
        // the time publish awaits readiness the session is already terminal.
        val engine = FakeEngine().apply { emitStoppedInsideStart = true }
        val client = clientWith(engine)

        val e = assertFailsWith<PortalException> {
            client.publish(siteConfig())
        }
        assertEquals(PortalFailure.Codes.TUNNEL_CLOSED, e.code)
        assertTrue(client.sessions.value.isEmpty())
        client.close()
    }

    @Test
    fun publishCancellationStopsSession() = runTest {
        val engine = FakeEngine().apply { statusActive = false }
        val client = clientWith(engine)

        val publishJob = async { client.publish(siteConfig()) }
        awaitStarted(engine)
        publishJob.cancel()

        assertFailsWith<kotlinx.coroutines.CancellationException> { publishJob.await() }
        assertEquals(engine.startedIds.toList(), engine.stoppedIds.toList())
        assertTrue(client.sessions.value.isEmpty())
        client.close()
    }

    @Test
    fun publishCleanupFailureLeavesRetryableSession() = runTest {
        val engine = FakeEngine().apply { statusActive = false }
        val client = clientWith(engine)
        engine.stopFailure = PortalException(PortalFailure.Codes.INTERNAL_ERROR, "stop boom")

        val e = assertFailsWith<PortalException> {
            client.publish(siteConfig(), timeoutMillis = 50)
        }
        assertEquals("publish_cleanup", e.failure.operation)
        assertTrue(e.failure.message.contains("STOP_TIMEOUT"))

        val tunnel = client.sessions.value.single()
        assertEquals(TunnelPhase.STOPPING, tunnel.state.value.phase)

        engine.stopFailure = null
        tunnel.stop()
        assertEquals(TunnelPhase.STOPPED, tunnel.state.value.phase)
        assertTrue(client.sessions.value.isEmpty())
        client.close()
    }

    /**
     * Waits until the fake engine's native start has minted a tunnel id.
     * `engine.start` runs on Dispatchers.Default, so a single `yield()` does
     * not guarantee the id exists before the test emits events for it.
     */
    private suspend fun awaitStarted(engine: FakeEngine) {
        var spins = 0
        while (engine.startedIds.isEmpty() && spins < 10_000) {
            yield()
            spins++
        }
        assertTrue(engine.startedIds.isNotEmpty(), "native start did not produce a tunnel id")
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

    @Test
    fun publishExposesPublicUrlDirectly() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)

        val tunnel = client.publish(siteConfig())

        assertEquals("https://site.portal.example", tunnel.publicUrl)
        assertEquals(tunnel.state.value.primaryPublicUrl, tunnel.publicUrl)
        client.close()
    }

    @Test
    fun publishReadinessFailureCarriesOperationAndTerminalPhase() = runTest {
        val engine = FakeEngine().apply { emitStoppedInsideStart = true }
        val client = clientWith(engine)

        val e = assertFailsWith<PortalException> {
            client.publish(siteConfig())
        }
        assertEquals(PortalFailure.Codes.TUNNEL_CLOSED, e.code)
        assertEquals("publish", e.failure.operation)
        assertEquals(TunnelPhase.STOPPED, e.failure.terminalPhase)
        client.close()
    }

    @Test
    fun publishCleanupFailureExposesReadinessFailure() = runTest {
        val engine = FakeEngine().apply { statusActive = false }
        val client = clientWith(engine)
        engine.stopFailure = PortalException(PortalFailure.Codes.INTERNAL_ERROR, "stop boom")

        val e = assertFailsWith<PortalException> {
            client.publish(siteConfig(), timeoutMillis = 50)
        }
        assertEquals("publish_cleanup", e.failure.operation)
        assertEquals(PortalFailure.Codes.STOP_TIMEOUT, e.failure.readinessFailure?.code)
        assertEquals("publish", e.failure.readinessFailure?.operation)

        engine.stopFailure = null
        client.sessions.value.single().stop()
        client.close()
    }

    @Test
    fun diagnosticsReportEngineRelayAndLastFailure() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        val tunnel = client.open(siteConfig())

        engine.emit(tunnel.tunnelId, "ERROR", """{"error":"relay down"}""")

        val d = client.diagnostics()
        assertTrue(d.engineVersion.isNotBlank())
        val session = d.sessions.single { it.sessionId == tunnel.tunnelId }
        assertEquals("https://relay.fake", session.activeRelay)
        assertEquals(PortalFailure.Codes.PROTOCOL_ERROR, session.lastFailure?.code)
        client.close()
    }

    @Test
    fun unsupportedEngineFeaturesFailBeforeCreatingNativeSessions() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        try {
            val unsupported = listOf(
                siteConfig().copy(ech = true),
                siteConfig().copy(x402 = PortalX402Config("0xrecipient")),
                PortalConfig.routes(listOf(PortalHTTPRoute("/paid", upstream = "http://127.0.0.1:8080", amount = "1"))),
                PortalConfig.routes(listOf(PortalHTTPRoute("/api", upstream = "http://127.0.0.1:8080", methods = listOf("GET"))))
            )
            for (config in unsupported) {
                val failure = assertFailsWith<PortalException> { client.publish(config) }
                assertEquals(PortalFailure.Codes.UNSUPPORTED_CAPABILITY, failure.code)
            }
            assertTrue(engine.startedIds.isEmpty())
            assertTrue(client.sessions.value.isEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun diagnosticsRedactIdentitySecretsWithoutLosingFailureContext() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        val identity = """{"name":"operator","address":"0xpublic","private_key":"private-material"}"""
        try {
            val tunnel = client.open(siteConfig().copy(identityJson = identity))
            engine.statusFailure = PortalException(PortalFailure(
                code = PortalFailure.Codes.PROTOCOL_ERROR,
                message = "relay rejected identity $identity",
                operation = "getStatus",
                nativeCode = 7,
                readinessFailure = PortalFailure(
                    PortalFailure.Codes.IDENTITY_INVALID,
                    "invalid private key private-material"
                )
            ))
            assertFailsWith<PortalException> { tunnel.refresh() }
            val diagnostics = client.diagnostics()
            val text = diagnostics.toString()
            assertFalse(text.contains(identity))
            assertFalse(text.contains("private-material"))
            val failure = assertNotNull(diagnostics.sessions.single().lastFailure)
            assertTrue(failure.message.contains("relay rejected identity"))
            assertEquals("getStatus", failure.operation)
            assertEquals(7, failure.nativeCode)
            assertEquals(PortalFailure.Codes.IDENTITY_INVALID, failure.readinessFailure?.code)
            // Export redaction does not destroy the original observable error.
            assertTrue(tunnel.state.value.lastFailure!!.message.contains("private-material"))
        } finally {
            client.close()
        }
    }

    @Test
    fun diagnosticsOnlyExportRelayOriginWithoutUrlSecrets() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        try {
            val tunnel = client.open(siteConfig())
            engine.emit(tunnel.tunnelId, "STATUS_CHANGED", PortalJson.encodeToString(PortalStatus(
                tunnelId = tunnel.tunnelId,
                name = "site",
                address = "0xpublic",
                active = true,
                relays = listOf(PortalRelayStatus(
                    relayUrl = "https://relay.example/private-path?token=secret#fragment",
                    state = "ready"
                ))
            )))
            assertEquals("https://relay.example", client.diagnostics().sessions.single().activeRelay)
        } finally {
            client.close()
        }
    }

    @Test
    fun malformedStatusDoesNotEchoUntrustedPayloadIntoFailure() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)
        try {
            val tunnel = client.open(siteConfig())
            engine.statusJsonOverride = """{"active":"secret-request-body"}"""
            val failure = assertFailsWith<PortalException> { tunnel.refresh() }
            assertEquals(PortalFailure.Codes.PROTOCOL_ERROR, failure.code)
            assertFalse(failure.message.orEmpty().contains("secret-request-body"))
            assertFalse(client.diagnostics().toString().contains("secret-request-body"))
        } finally {
            client.close()
        }
    }

    @Test
    fun useClosesClientAfterBlock() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)

        val url = client.use { c ->
            c.publish(siteConfig()).publicUrl
        }

        assertEquals("https://site.portal.example", url)
        assertTrue(client.isClosed)
        assertTrue(client.sessions.value.isEmpty())
    }

    @Test
    fun usePropagatesBlockFailureAndStillCloses() = runTest {
        val engine = FakeEngine()
        val client = clientWith(engine)

        assertFailsWith<IllegalStateException> {
            client.use {
                it.open(siteConfig())
                throw IllegalStateException("boom")
            }
        }
        assertTrue(client.isClosed)
        assertEquals(engine.startedIds.toList(), engine.stoppedIds.toList())
    }
}
