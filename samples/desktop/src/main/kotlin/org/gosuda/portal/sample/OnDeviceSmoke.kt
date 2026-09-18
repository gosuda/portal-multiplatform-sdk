package org.gosuda.portal.sample

import kotlinx.coroutines.runBlocking
import org.gosuda.portal.sample.content.ondevice.OnDeviceModelContent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Headless check for the on-device content: starts the loopback server,
 * exercises /v1/generate (Markov fallback or Ollama), /v1/model, and
 * /v1/health, then stops. Proves the endpoint works without the Compose UI.
 *
 * Run: `./gradlew :samples:desktop:ondeviceSmoke`
 */
fun main() = runBlocking {
    val content = OnDeviceModelContent
    content.start()
    val http = HttpClient.newBuilder().build()
    try {
        fun get(path: String): Pair<Int, String> {
            val r = http.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${content.PORT}$path")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            return r.statusCode() to r.body()
        }

        val (hCode, health) = get("/v1/health")
        println("GET /v1/health -> $hCode  $health")
        check(hCode == 200)

        val (mCode, model) = get("/v1/model")
        println("GET /v1/model -> $mCode  $model")
        check(mCode == 200)

        val (gCode, gen) = get("/v1/generate?prompt=portal&max_tokens=40")
        println("GET /v1/generate -> $gCode")
        check(gCode == 200)
        check(gen.contains("\"text\"")) { "generate missing text field" }
        println("  text: ${gen.take(160)}…")

        val (iCode, _) = get("/")
        check(iCode == 200)
        println("GET / -> $iCode (playground)")

        println("ONDEVICE SMOKE OK")
    } finally {
        content.stop()
    }
}
