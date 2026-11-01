package org.gosuda.portal.sample

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.gosuda.portal.Capability
import org.gosuda.portal.PortalDesktop
import org.gosuda.portal.PortalSnapshot
import org.gosuda.portal.sample.llm.LlmPassword
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Local checks use real sample services, not a fake engine. --relay adds actual SDK validation/publish. */
fun main(args: Array<String>) = runBlocking {
    if ("--list-models" in args) {
        val store = DesktopModels.store()
        store.models.forEach {
            println("${it.id} | ${it.title} | ${it.quantization} | ${it.downloadBytes} bytes | ${if (store.cachedFile(it.id) != null) "cached" else "not downloaded"}")
        }
        println("CACHE ${DesktopModels.cacheDirectory}")
        return@runBlocking
    }
    args.firstOrNull { it.startsWith("--download-model=") }?.substringAfter('=')?.let { id ->
        val store = DesktopModels.store()
        val model = store.model(id)
        val reporting = launch {
            store.progress.collect { state ->
                println("MODEL ${state.phase} ${state.receivedBytes}/${state.totalBytes}")
            }
        }
        try {
            store.download(id)
            checkNotNull(store.cachedFile(id)) { "Download did not produce a verified cached file." }
            println("MODEL CACHED ${model.id} in ${DesktopModels.cacheDirectory}")
        } finally { reporting.cancelAndJoin() }
        return@runBlocking
    }
    val relay = "--relay" in args
    val domainPrefix = args.firstOrNull { it.startsWith("--domain-prefix=") }?.substringAfter('=')
        ?: "desktop-demo"
    val selected = args.firstOrNull { it.startsWith("--mode=") }?.substringAfter('=')
        ?.takeUnless { it.equals("all", ignoreCase = true) }
        ?.let { WorkbenchMode.valueOf(it.uppercase()) }
    // ALL deliberately remains the five transport demonstrations: never download/load a model.
    val modes = selected?.let(::listOf) ?: WorkbenchMode.entries.filter { it != WorkbenchMode.LOCAL_LLM }
    val modelId = args.firstOrNull { it.startsWith("--model=") }?.substringAfter('=').orEmpty()
    val models = if (selected == WorkbenchMode.LOCAL_LLM) DesktopModels.store().also {
        require(modelId.isNotBlank()) { "LOCAL_LLM requires --model=<catalog-id>; use --list-models." }
        require(it.cachedFile(modelId) != null) { "Model is not cached. Run --download-model=$modelId explicitly first." }
    } else null
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    if (!relay) {
        require(selected != WorkbenchMode.STATIC) { "Static serving requires --relay." }
        // Recreate all listeners and payloads to exercise stop/restart without fixed-port collisions.
        repeat(2) { pass ->
            for (mode in modes) {
                if (mode == WorkbenchMode.STATIC) continue
                val password = if (mode == WorkbenchMode.LOCAL_LLM) LlmPassword.generate() + "🔒한글" else null
                val demo = withContext(Dispatchers.IO) {
                    LocalDemo.prepare(WorkbenchRequest(mode, modelId = modelId, domainPrefix = domainPrefix), models, password)
                }
                val config = demo.config
                demo.use {
                    when (mode) {
                        WorkbenchMode.STATIC -> error("Static mode requires the native relay smoke")
                        WorkbenchMode.LOCAL_LLM -> localLlm(http, "http://${config.targetAddr}", checkNotNull(password))
                        WorkbenchMode.HTTP -> {
                            val base = "http://${config.targetAddr}"
                            get(http, "$base/api/status", "portal-api")
                            post(http, "$base/webhook?secret=not-logged", "webhook receiver")
                            check(demo.activity.value.count == 2L)
                            check(demo.activity.value.lines.none { it.contains("secret") || it.contains("body-not-logged") })
                            repeat(45) { get(http, "$base/api/status", "portal-api") }
                            check(demo.activity.value.count == 47L && demo.activity.value.lines.size == 40)
                            val oversized = http.send(
                                HttpRequest.newBuilder(URI("$base/webhook")).timeout(Duration.ofSeconds(10))
                                    .POST(HttpRequest.BodyPublishers.ofString("x".repeat(16_385))).build(),
                                HttpResponse.BodyHandlers.ofString())
                            check(oversized.statusCode() == 413) { "Oversized webhook body was accepted" }
                        }
                        WorkbenchMode.ROUTES -> {
                            val routes = checkNotNull(config.httpRoutes)
                            get(http, "${routes[0].upstream}/api/status", "portal-api")
                            post(http, "${routes[1].upstream}/webhook", "webhook receiver")
                            check(demo.activity.value.count == 2L)
                        }
                        WorkbenchMode.TCP -> {
                            tcpEcho(checkNotNull(config.targetAddr))
                        }
                        WorkbenchMode.UDP -> {
                            udpEcho(checkNotNull(config.udpAddr))
                        }
                    }
                    println("LOCAL ${mode.name} pass ${pass + 1} OK")
                }
                val streamTargets = config.httpRoutes.orEmpty().mapNotNull { it.upstream }
                    .map { address(it) } + listOfNotNull(config.targetAddr?.let(::address))
                streamTargets.forEach { target ->
                    val stillListening = runCatching {
                        Socket().use { it.connect(target, 1_000) }
                    }.isSuccess
                    check(!stillListening) { "Closed demo still accepts connections: $target" }
                }
                config.udpAddr?.let { target ->
                    DatagramSocket(null).use { it.bind(address(target)) }
                }
            }
        }
        println("WORKBENCH SMOKE OK (real local HTTP/routes/TCP/UDP; static serving requires --relay)")
    } else {
        for (mode in modes) {
            val password = if (mode == WorkbenchMode.LOCAL_LLM) LlmPassword.generate() + "🔒한글" else null
            val demo = withContext(Dispatchers.IO) { LocalDemo.prepare(WorkbenchRequest(mode,
                modelId = modelId, domainPrefix = domainPrefix,
                relays = commaValues(System.getenv("PORTAL_SMOKE_RELAYS").orEmpty()),
                discovery = "--no-discovery" !in args), models, password) }
            val client = try { PortalDesktop.client("org.gosuda.portal.sample.smoke") }
                catch (failure: Exception) { demo.close(); throw failure }
            try {
                val tunnel = client.publish(demo.config, timeoutMillis = 60_000)
                val capability = when (mode) {
                    WorkbenchMode.TCP -> Capability.TCP
                    WorkbenchMode.UDP -> Capability.UDP
                    else -> Capability.HTTP_TLS
                }
                val snapshot = tunnel.awaitReady(capability, timeoutMillis = 60_000)
                when (mode) {
                    WorkbenchMode.STATIC -> staticSite(http, url(snapshot))
                    WorkbenchMode.LOCAL_LLM -> localLlm(http, url(snapshot), checkNotNull(password))
                    WorkbenchMode.HTTP, WorkbenchMode.ROUTES -> {
                        get(http, "${url(snapshot)}/api/status", "portal-api")
                        post(http, "${url(snapshot)}/webhook", "webhook receiver")
                        check(demo.activity.value.count >= 2L)
                    }
                    WorkbenchMode.TCP -> tcpEcho(checkNotNull(snapshot.relays
                        .firstOrNull { it.isReady && !it.tcpAddr.isNullOrBlank() }?.tcpAddr))
                    WorkbenchMode.UDP -> udpEcho(checkNotNull(snapshot.relays
                        .firstOrNull { it.isReady && !it.udpAddr.isNullOrBlank() }?.udpAddr))
                }
                tunnel.refresh()
                println("RELAY ${mode.name} round-trip OK: preferred=${snapshot.primaryPublicUrl}; canonical=${snapshot.publicUrls}; relays=${snapshot.relays}")
                tunnel.stop()
            } finally {
                try { client.close() } finally {
                    // A native stop failure must not tear the upstream out from under a retained session.
                    if (client.sessions.value.isEmpty()) demo.close()
                }
            }
        }
        println("REAL RELAY SMOKE OK")
    }
}

/** Exercise the actual server and model; credentials, prompts and generated text never enter logs. */
private fun localLlm(http: HttpClient, base: String, password: String) {
    val page = getResponse(http, "$base/")
    check(page.statusCode() == 200 && page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"))
    for (path in listOf("llm.css", "llm.js")) {
        check(getResponse(http, "$base/$path").statusCode() == 200) { "Missing local AI asset: $path" }
    }
    check(getResponse(http, "$base/api/status").statusCode() == 401) { "Unauthenticated status was accepted" }
    val wrongPassword = LlmPassword.generate()
    val wrongAuthorization = LlmPassword.authorization(wrongPassword)
    val authorization = LlmPassword.authorization(password)
    val wrongCredentials = http.send(HttpRequest.newBuilder(URI("$base/api/status"))
        .timeout(Duration.ofSeconds(30)).header("Authorization", wrongAuthorization).GET().build(),
        HttpResponse.BodyHandlers.ofString())
    check(wrongCredentials.statusCode() == 401) { "Incorrect password was accepted" }
    val status = http.send(HttpRequest.newBuilder(URI("$base/api/status"))
        .timeout(Duration.ofSeconds(30)).header("Authorization", authorization).GET().build(),
        HttpResponse.BodyHandlers.ofString())
    check(status.statusCode() == 200 && Json.parseToJsonElement(status.body()).jsonObject["local"]?.jsonPrimitive?.content == "true")
    val body = buildJsonObject {
        put("prompt", "Write one short sentence about the moon.")
        put("maxTokens", 48)
    }.toString()
    fun generate(credentials: String?) = http.send(
        HttpRequest.newBuilder(URI("$base/api/generate")).timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json").apply {
                if (credentials != null) header("Authorization", credentials)
            }.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    check(generate(null).statusCode() == 401) { "Unauthenticated generation was accepted" }
    check(generate(wrongAuthorization).statusCode() == 401) { "Incorrect generation password was accepted" }
    val response = generate(authorization)
    check(response.statusCode() == 200) { "Device-local inference failed: HTTP ${response.statusCode()}" }
    val result = Json.parseToJsonElement(response.body()).jsonObject
    check(!result["text"]?.jsonPrimitive?.content.isNullOrBlank()) { "Native inference returned no generated text" }
    println("LOCAL_LLM real generation + missing/wrong-password authentication OK")
}

private fun staticSite(http: HttpClient, base: String) {
    val index = getResponse(http, "$base/")
    check(index.statusCode() == 200 && index.headers().firstValue("Content-Type").orElse("").startsWith("text/html"))
    val script = getResponse(http, "$base/arcade.js")
    check(script.statusCode() == 200 && script.headers().firstValue("Content-Type").orElse("").contains("javascript")) {
        "Static JavaScript was missing or had an invalid module MIME type"
    }
}

private fun getResponse(http: HttpClient, url: String): HttpResponse<String> =
    http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).GET().build(),
        HttpResponse.BodyHandlers.ofString())

private fun url(snapshot: PortalSnapshot): String = checkNotNull(snapshot.primaryPublicUrl).trimEnd('/')

private fun get(http: HttpClient, url: String, marker: String) {
    val response = getResponse(http, url)
    check(response.statusCode() == 200 && response.body().contains(marker)) {
        "GET $url: ${response.statusCode()}, expected marker $marker"
    }
}

private fun post(http: HttpClient, url: String, marker: String) {
    val response = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofString("body-not-logged")).build(), HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() == 200 && response.body().contains(marker)) { "POST $url: ${response.statusCode()}" }
}

private fun address(value: String): InetSocketAddress {
    val uri = URI(if ("://" in value) value else "tcp://$value")
    check(uri.host != null && uri.port in 1..65535) { "Invalid raw endpoint: $value" }
    return InetSocketAddress(uri.host, uri.port)
}

private fun tcpEcho(endpoint: String) {
    val marker = "portal-tcp-echo\n".toByteArray()
    Socket().use { socket ->
        socket.connect(address(endpoint), 15_000)
        socket.soTimeout = 15_000
        socket.getOutputStream().write(marker)
        socket.getOutputStream().flush()
        check(socket.getInputStream().readNBytes(marker.size).contentEquals(marker)) { "TCP echo mismatch" }
    }
}

private fun udpEcho(endpoint: String) {
    val marker = "portal-udp-echo".toByteArray()
    DatagramSocket().use { socket ->
        socket.soTimeout = 15_000
        socket.connect(address(endpoint))
        socket.send(DatagramPacket(marker, marker.size))
        val response = DatagramPacket(ByteArray(4096), 4096)
        socket.receive(response)
        check(response.data.copyOf(response.length).contentEquals(marker)) { "UDP echo mismatch" }
    }
}
