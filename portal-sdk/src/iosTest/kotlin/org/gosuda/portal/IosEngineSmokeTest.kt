package org.gosuda.portal

import org.gosuda.portal.internal.platformNativeEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises the real `libportaltunnel` Go bridge through the cinterop
 * adapter. These paths need no network: identity generation/parsing and
 * config validation all fail or succeed inside the engine process.
 */
class IosEngineSmokeTest {

    private val engine = platformNativeEngine()

    @Test
    fun generateAndParseIdentityRoundTrips() {
        val raw = engine.generateIdentity("smoke-test")
        assertTrue(raw.contains("\"address\""), "identity document should carry an address")

        val parsed = engine.parseIdentity(raw)
        val identity = PortalIdentity.parse(parsed)
        assertEquals("smoke-test", identity.name)
        assertTrue(identity.address.isNotEmpty())
    }

    @Test
    fun parseIdentityRejectsGarbage() {
        assertFailsWith<PortalException> {
            engine.parseIdentity("not json")
        }
    }

    @Test
    fun startRejectsEmptyRelaySet() {
        // discovery=false with no relays must fail inside the engine, not in
        // Kotlin validation (which is bypassed at this layer).
        assertFailsWith<PortalException> {
            engine.start("""{"discovery":false,"name":"smoke"}""")
        }
    }

    @Test
    fun stopRejectsUnknownTunnel() {
        assertFailsWith<PortalException> {
            engine.stop("tunnel_does_not_exist")
        }
    }
}

