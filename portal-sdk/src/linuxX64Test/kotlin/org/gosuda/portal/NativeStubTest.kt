package org.gosuda.portal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Exercises the real cinterop adapter against the C stub
 * (native/stub/portaltunnel_stub.c) linked into this test binary. Verifies
 * symbol resolution, out-param strings, PortalFreeString ownership, and the
 * staticCFunction event path — the same code path iOS uses.
 */
class NativeStubTest {

    @Test
    fun identityGenerateAndParseThroughCAbi() {
        val identity = PortalIdentity.generate("stub-user")
        assertEquals("stub-user", identity.name)
        assertEquals("0xstubidentity", identity.address)

        val parsed = PortalIdentity.parse(identity.document)
        assertEquals(identity.address, parsed.address)

        assertFailsWith<PortalException> {
            PortalIdentity.parse("""{"name":"no-address"}""")
        }
    }

    @Test
    fun tunnelLifecycleThroughCAbi() = runTest {
        val client = PortalClient()
        val tunnel = client.open(
            PortalConfig(
                name = "stub",
                staticDir = "/data/site",
                staticIndex = "index.html",
                relays = listOf("https://relay.stub"),
                discovery = false
            )
        )

        // The stub emits STARTED + STATUS_CHANGED inside PortalStart, before
        // the tunnel id is returned — the orphan buffer must deliver them.
        val snapshot = tunnel.state.value
        assertEquals(TunnelPhase.ACTIVE, snapshot.phase)
        assertEquals(listOf("https://stub.portal.example"), snapshot.publicUrls)
        assertTrue(snapshot.relays.first().isReady)

        tunnel.stop()
        assertEquals(TunnelPhase.STOPPED, tunnel.state.value.phase)
        client.close()
    }
}
