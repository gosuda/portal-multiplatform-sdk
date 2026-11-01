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
import org.gosuda.portal.internal.ConfigValidation

class PortalConfigTest {

    @Test
    fun configStringsDoNotExposeIdentityOrPaymentCredentials() {
        val identity = """{"private_key":"identity-secret"}"""
        val token = "facilitator-secret"
        val payment = PortalX402Config(payTo = "0xabc", facilitatorToken = token)
        val config = PortalConfig(identityJson = identity, x402 = payment)

        assertFalse(payment.toString().contains(token))
        assertFalse(config.toString().contains("identity-secret"))
        assertFalse(config.toString().contains(token))

        // Redaction must not alter the secrets actually passed to the engine.
        val wire = PortalJson.parseToJsonElement(PortalJson.encodeToString(config)).jsonObject
        assertEquals(identity, wire["identity_json"]?.jsonPrimitive?.content)
        assertEquals(token, wire["x402"]?.jsonObject?.get("facilitator_token")?.jsonPrimitive?.content)
    }

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

    private fun validate(config: PortalConfig, allowRemoteTargets: Boolean = false) {
        ConfigValidation.validate(config, allowRemoteTargets, Capability.entries.toSet())
    }

    @Test
    fun domainPrefixRejectsInvalidLabelsAndCanonicalIdentityShapes() {
        for (prefix in listOf("", " ", "-arcade", "arcade-", "two.labels", "two words",
            "a".repeat(64), "a".repeat(40), "arcade-${"0".repeat(40)}")) {
            val error = assertFailsWith<PortalException> {
                validate(PortalConfig.http("127.0.0.1:8080").withDomainPrefix(prefix))
            }
            assertEquals(PortalFailure.Codes.INVALID_CONFIG, error.code)
        }
    }

    @Test
    fun bracketedIpv6LoopbackRelaysAndTargetsAreAccepted() {
        for (host in listOf("::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1")) {
            val relay = "http://[$host]:8443/relay"
            assertEquals("https://[$host]:8443", ConfigValidation.normalizeRelayUrl(relay))
            validate(PortalConfig(targetAddr = "[$host]:8080", udpAddr = "[$host]:8081"))
        }
    }

    @Test
    fun malformedIpv6HostsAreRejectedEvenWithRemoteTargetsEnabled() {
        for (host in listOf(":::", "1:2:3:4:5:6:7::8", ":0:0:0:0:0:0:0:1", "localhost")) {
            assertFailsWith<PortalException> {
                ConfigValidation.normalizeRelayUrl("https://[$host]:8443")
            }
            assertFailsWith<PortalException> {
                validate(PortalConfig(targetAddr = "[$host]:8080"), allowRemoteTargets = true)
            }
        }
    }

    @Test
    fun wildcardAndRemoteTargetsRequireExplicitOptIn() {
        for (target in listOf("0.0.0.0:8080", "[::]:8080", "[2001:db8::1]:8080", "10.0.0.5:8080")) {
            val config = PortalConfig(targetAddr = target, udpAddr = target)
            val failure = assertFailsWith<PortalException> { validate(config) }
            assertEquals(PortalFailure.Codes.INVALID_CONFIG, failure.code)
            validate(config, allowRemoteTargets = true)
        }
    }

    @Test
    fun routedUpstreamsCannotBypassLoopbackPolicy() {
        for (upstream in listOf("http://10.0.0.5:8080/api", "https://remote.example/api")) {
            val config = PortalConfig.routes(listOf(PortalHTTPRoute("/api", upstream = upstream)))
            val failure = assertFailsWith<PortalException> { validate(config) }
            assertEquals(PortalFailure.Codes.INVALID_CONFIG, failure.code)
            validate(config, allowRemoteTargets = true)
        }
        for (upstream in listOf("http://127.0.0.1/api", "https://[::1]/api", "http://localhost:8080")) {
            validate(PortalConfig.routes(listOf(PortalHTTPRoute("/api", upstream = upstream))))
        }
    }

    @Test
    fun routedStaticFilesUseTheSameTraversalRestrictionsAsTopLevelFiles() {
        for (route in listOf(
            PortalHTTPRoute("/", staticRoot = "/data/../secrets"),
            PortalHTTPRoute("/", staticRoot = " "),
            PortalHTTPRoute("/", staticRoot = "/data/site", staticIndex = "../secret")
        )) {
            assertFailsWith<PortalException> { validate(PortalConfig.routes(listOf(route))) }
        }
    }

    @Test
    fun metadataLimitCountsUtf8BytesRatherThanUtf16Characters() {
        ConfigValidation.validateMetadataJson("""{"description":"${"a".repeat(6000)}"}""")
        ConfigValidation.validateMetadataJson("""{"description":"${"\uD801\uDC00".repeat(3000)}"}""")
        val failure = assertFailsWith<PortalException> {
            ConfigValidation.validateMetadataJson("""{"description":"${"한".repeat(6000)}"}""")
        }
        assertEquals(PortalFailure.Codes.INVALID_CONFIG, failure.code)
    }


}
