package org.gosuda.portal

import org.gosuda.portal.internal.DesktopNativeLibraryLoader
import org.gosuda.portal.internal.DesktopPortalEngine
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the JNA adapter against the real C stub
 * (`native/stub/portaltunnel_stub.c`) compiled as a shared library. These
 * tests verify the memory/callback contract, not a mock of JNA calls.
 */
class DesktopEngineTest {

    private val stubPath: Path = Path.of(
        System.getProperty("portal.test.stubLibrary")
            ?: error("portal.test.stubLibrary system property not set")
    )

    private fun engine(): DesktopPortalEngine = DesktopPortalEngine(stubPath)

    @Test
    fun identityRoundTripsThroughJna() {
        val e = engine()
        val raw = e.generateIdentity("desktop-test")
        assertTrue(raw.contains("\"address\""), "identity should carry an address: $raw")
        val parsed = e.parseIdentity(raw)
        assertTrue(parsed.contains("desktop-test"))
    }

    @Test
    fun utf8InputsAndOutputsSurvive() {
        val e = engine()
        val raw = e.generateIdentity("테스트-名前-✓")
        assertTrue(raw.contains("테스트-名前-✓"), "utf8 name should round-trip: $raw")
    }

    @Test
    fun parseIdentityRejectsGarbageWithStructuredError() {
        val e = engine()
        val ex = assertFailsWith<PortalException> {
            e.parseIdentity("not json")
        }
        assertEquals(PortalFailure.Codes.INTERNAL_ERROR, ex.code)
        assertEquals("parseIdentity", ex.failure.operation)
        assertNotNull(ex.failure.nativeCode)
    }

    @Test
    fun stopRejectsUnknownTunnelWithStructuredError() {
        val e = engine()
        val ex = assertFailsWith<PortalException> {
            e.stop("tunnel_does_not_exist")
        }
        assertEquals("stop", ex.failure.operation)
    }

    @Test
    fun repeatedCallsDoNotLeakOrCrash() {
        val e = engine()
        // Each call allocates out/error strings that must be freed exactly
        // once; a leak or double-free would crash or corrupt the heap.
        repeat(200) {
            e.generateIdentity("iter-$it")
        }
    }

    @Test
    fun startEmitsEventsThroughCallback() {
        val e = engine()
        val events = mutableListOf<Triple<String, String, String>>()
        e.setEventListener { id, type, payload -> events.add(Triple(id, type, payload)) }
        val tunnelId = e.start("""{"name":"cb-test","discovery":false,"relays":["https://relay.stub"]}""")
        assertTrue(events.any { it.first == tunnelId && it.second == "STARTED" })
        assertTrue(events.any { it.second == "STATUS_CHANGED" })
    }

    @Test
    fun callbackExceptionIsContainedAtBoundary() {
        val e = engine()
        e.setEventListener { _, _, _ -> throw RuntimeException("listener blew up") }
        // The stub emits STARTED + STATUS_CHANGED inside start; a throwing
        // listener must not crash the process or fail the call.
        val tunnelId = e.start("""{"name":"cb-throw","discovery":false,"relays":["https://relay.stub"]}""")
        assertTrue(tunnelId.isNotEmpty())
    }

    @Test
    fun getStatusReturnsStubStatus() {
        val e = engine()
        val status = e.getStatus("any-id")
        assertTrue(status.contains("\"active\":true"))
        assertTrue(status.contains("stub.portal.example"))
    }

    @Test
    fun stopAllDoesNotThrow() {
        engine().stopAll()
    }
}
