package org.gosuda.portal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gosuda.portal.internal.PortalJson

class PortalConfigTest {

    @Test
    fun defaultsEncodeAsAbsentKeys() {
        val json = PortalJson.encodeToString(PortalConfig(name = "n"))
        val obj = PortalJson.parseToJsonElement(json).jsonObject
        // encodeDefaults=false: defaults must stay absent so the native
        // runtime applies its own defaults (wire contract).
        assertEquals("n", obj["name"]?.jsonPrimitive?.content)
        assertFalse("discovery" in obj)
        assertFalse("max_active_relays" in obj)
        assertFalse("hide" in obj)
    }

    @Test
    fun wireKeysMatchV1Contract() {
        val config = PortalConfig(
            name = "n",
            identityJson = "id",
            relays = listOf("https://r.example"),
            discovery = false,
            maxActiveRelays = 2,
            banMitm = true,
            staticDir = "/data/site",
            staticIndex = "index.html",
            targetAddr = "127.0.0.1:8080",
            httpRoutes = listOf(
                PortalHTTPRoute(prefix = "/api", upstream = "http://127.0.0.1:9")
            ),
            x402 = PortalX402Config(payTo = "0xabc", testnet = true)
        )
        val obj = PortalJson.parseToJsonElement(PortalJson.encodeToString(config)).jsonObject
        val expected = setOf(
            "name", "identity_json", "relays", "discovery", "max_active_relays",
            "ban_mitm", "static_dir", "static_index", "target_addr",
            "http_routes", "x402"
        )
        assertTrue(obj.keys.containsAll(expected), "missing keys: ${expected - obj.keys}")
        val route = obj["http_routes"]!!.let {
            PortalJson.decodeFromString<List<PortalHTTPRoute>>(it.toString()).first()
        }
        assertEquals("/api", route.prefix)
        val x402 = obj["x402"]!!.let {
            PortalJson.parseToJsonElement(it.toString()).jsonObject
        }
        assertEquals("0xabc", x402["pay_to"]?.jsonPrimitive?.content)
    }

    @Test
    fun builtConfigIsImmutableWhenBuilderChanges() {
        val builder = PortalConfig.Builder().addRelay("https://first.example")
        val first = builder.build()
        builder.addRelay("https://second.example")
        assertEquals(listOf("https://first.example"), first.relays)
    }

    @Test
    fun builderProducesSameWireShape() {
        val built = PortalConfig.Builder()
            .setName("s")
            .setStaticSite("/data/site", "index.html")
            .setDiscovery(true)
            .setMaxActiveRelays(2)
            .addTag("android")
            .build()
        assertEquals("s", built.name)
        assertEquals("/data/site", built.staticDir)
        assertEquals(listOf("android"), built.tags)
    }
}
