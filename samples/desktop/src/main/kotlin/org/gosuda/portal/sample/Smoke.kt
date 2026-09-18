package org.gosuda.portal.sample

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.gosuda.portal.Capability
import org.gosuda.portal.PortalDesktop
import org.gosuda.portal.portalConfig
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Headless publish smoke: starts a loopback HTTP server, opens a real Portal
 * tunnel through relay discovery, waits for the public URL, fetches it, and
 * stops. Proves the desktop engine works end-to-end without the Compose UI.
 *
 * Run: `./gradlew :samples:desktop:smoke`
 */
fun main() = runBlocking {
    val port = 8081
    val marker = "portal-desktop-smoke-${System.currentTimeMillis()}"

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/") { ex ->
        val body = "<html><body><h1>$marker</h1></body></html>".toByteArray()
        ex.responseHeaders.add("Content-Type", "text/html")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }
    server.start()
    println("local server on 127.0.0.1:$port (marker=$marker)")

    val client = PortalDesktop.client("org.gosuda.portal.sample.smoke")
    try {
        val tunnel = client.open(
            portalConfig {
                setName("desktop-smoke")
                setTargetAddress("127.0.0.1:$port")
                setDiscovery(true)
            }
        )
        println("tunnel opened, waiting for public URL…")
        val snap = tunnel.awaitReady(Capability.HTTP_TLS, timeoutMillis = 60_000)
        val url = snap.primaryPublicUrl ?: error("no public URL in snapshot: $snap")
        println("public URL: $url")

        // Fetch through the relay — the marker must round-trip.
        val http = HttpClient.newBuilder().build()
        val resp = http.send(
            HttpRequest.newBuilder(URI(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        println("GET $url -> ${resp.statusCode()}")
        check(resp.statusCode() == 200) { "expected 200, got ${resp.statusCode()}" }
        check(resp.body().contains(marker)) { "response missing marker" }
        println("marker round-tripped through relay ✓")

        tunnel.stop()
        println("tunnel stopped cleanly")
    } finally {
        client.close()
        server.stop(0)
    }
    println("SMOKE OK")
}
