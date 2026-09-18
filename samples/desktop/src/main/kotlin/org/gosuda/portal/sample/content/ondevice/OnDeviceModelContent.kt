package org.gosuda.portal.sample.content.ondevice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.sample.content.PublishableContent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * On-device LLM served over HTTP, exposed through `target_addr` — the
 * desktop analogue of the Android LiteRT-LM content, backed by a locally
 * running Ollama daemon instead of an embedded engine.
 *
 * When Ollama is running and the selected model is pulled, `/v1/generate`
 * proxies to `http://127.0.0.1:11434/api/generate`. When the daemon or the
 * model is absent, a tiny embedded Markov chain answers instead — the API
 * shape is identical, so the demo works out of the box and upgrades to a
 * real LLM the moment Ollama serves the model.
 *
 * Endpoints:
 * - `GET /`                      → HTML playground (prompt form)
 * - `GET /v1/generate?prompt=&max_tokens=` → JSON completion
 * - `GET /v1/model`              → JSON model card (engine, backend, tag)
 * - `GET /v1/health`             → JSON liveness
 * - anything else                → JSON 404
 */
object OnDeviceModelContent : PublishableContent {
    override val id = "ondevice"
    override val title = "On-device model"
    override val summary = "Ollama LLM on this device — real inference, no cloud"
    override val detail: String
        get() = "target_addr → 127.0.0.1:$PORT · /v1/generate · engine: $engineBackend"

    const val PORT = 18080
    private const val MAX_INFLIGHT = 8
    private const val REQUEST_TIMEOUT_MS = 120_000L
    private const val MAX_TOKENS_CAP = 512

    private var server: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val requests = AtomicLong(0)
    private val rejected = AtomicLong(0)
    private val queued = AtomicLong(0)
    @Volatile private var startedAt = 0L

    // ---- engine state --------------------------------------------------------

    private var engineBackend: String = "none"
    private var engineTag: String? = null
    private val engineMutex = Mutex()
    private val fallbackModel = MarkovModel(CORPUS)

    /** Observable engine lifecycle for the picker card. */
    sealed interface EngineStatus {
        data object Idle : EngineStatus
        data class Loading(val backend: String) : EngineStatus
        data class Ready(val backend: String) : EngineStatus
        data object Unavailable : EngineStatus   // daemon/model absent → fallback
        data object Failed : EngineStatus
    }
    private val _engineStatus = MutableStateFlow<EngineStatus>(EngineStatus.Idle)
    val engineStatus: StateFlow<EngineStatus> = _engineStatus.asStateFlow()

    /** Reloads the engine on the newly selected model, if it is running. */
    fun onModelChanged() {
        if (engineTag != null || _engineStatus.value is EngineStatus.Loading) {
            scope?.launch { initEngine() }
        }
    }

    override suspend fun start() {
        OllamaModels.loadSelection()
        if (server != null) return
        val socket = ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))
        server = socket
        startedAt = System.currentTimeMillis()
        requests.set(0)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break // socket closed by stop()
                }
                launch { runCatching { handle(client) } }
            }
        }
        // Engine probe is slow; run it off the accept loop so the port is
        // listening immediately. Also re-probe when the managed daemon
        // finishes installing — the first probe may have fallen back.
        s.launch { initEngine() }
        s.launch {
            OllamaSetup.state.collect { setup ->
                if (setup is OllamaSetup.State.Running && engineTag == null) initEngine()
            }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
        runCatching { server?.close() }
        server = null
        engineBackend = "none"
        engineTag = null
        _engineStatus.value = EngineStatus.Idle
    }

    override fun baseConfig(): PortalConfig =
        PortalConfig.http("127.0.0.1:$PORT")

    // ---- engine lifecycle ---------------------------------------------------

    private suspend fun initEngine() {
        engineMutex.withLock {
            _engineStatus.value = EngineStatus.Loading("ollama")
            val spec = OllamaModels.selected.value
            try {
                if (!OllamaClient.isRunning()) {
                    // Kick the managed daemon/install path and give it a
                    // bounded window to come up before falling back.
                    OllamaSetup.ensureRunning()
                    val deadline = System.currentTimeMillis() + 25_000
                    while (!OllamaClient.isRunning() &&
                        System.currentTimeMillis() < deadline &&
                        OllamaSetup.state.value !is OllamaSetup.State.Failed) {
                        kotlinx.coroutines.delay(400)
                    }
                }
                if (!OllamaClient.isRunning()) {
                    engineBackend = "unavailable:ollama-not-running"
                    engineTag = null
                    _engineStatus.value = EngineStatus.Unavailable
                    return@withLock
                }
                if (!OllamaModels.isInstalled(spec)) {
                    engineBackend = "unavailable:model-not-pulled"
                    engineTag = null
                    _engineStatus.value = EngineStatus.Unavailable
                    return@withLock
                }
                engineBackend = "ollama:${spec.tag}"
                engineTag = spec.tag
                _engineStatus.value = EngineStatus.Ready(engineBackend)
            } catch (e: Exception) {
                engineBackend = "failed"
                engineTag = null
                _engineStatus.value = EngineStatus.Failed
            }
        }
    }

    // ---- HTTP handling ------------------------------------------------------

    private data class HttpResponse(
        val status: String,
        val contentType: String,
        val body: String,
        val headers: Map<String, String> = emptyMap()
    )

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val target = requestLine.split(' ').getOrNull(1) ?: "/"
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            requests.incrementAndGet()
            val res = route(path, query)
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            writer.write("HTTP/1.1 ${res.status}\r\n")
            writer.write("Content-Type: ${res.contentType}\r\n")
            writer.write("Content-Length: ${res.body.toByteArray(Charsets.UTF_8).size}\r\n")
            for ((k, v) in res.headers) writer.write("$k: $v\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.write(res.body)
            writer.flush()
        }
    }

    private fun route(path: String, query: String): HttpResponse = when (path) {
        "/" -> HttpResponse("200 OK", "text/html; charset=utf-8", indexHtml())
        "/v1/generate" -> generateResponse(query)
        "/v1/model" -> json("200 OK", modelJson())
        "/v1/health" -> json("200 OK", healthJson())
        else -> json("404 Not Found",
            """{"error":"not_found","path":${jsonString(path)},"endpoints":["/","/v1/generate","/v1/model","/v1/health"]}""")
    }

    // ---- concurrency model --------------------------------------------------
    // Inference slots bound the whole queue+execution: at most MAX_INFLIGHT
    // requests hold a slot; the rest get an immediate 429 instead of piling
    // up. Inside a slot, Ollama generation is serialized on engineMutex; the
    // Markov fallback is stateless and runs in parallel. Every wait is
    // time-boxed.
    private val inflight = java.util.concurrent.Semaphore(MAX_INFLIGHT)

    private fun generateResponse(query: String): HttpResponse {
        val params = query.split('&')
            .filter { it.contains('=') }
            .associate {
                val (k, v) = it.split('=', limit = 2)
                urlDecode(k) to urlDecode(v)
            }
        val prompt = params["prompt"].orEmpty().ifBlank { "Portal" }
        val maxTokens = (params["max_tokens"]?.toIntOrNull() ?: 256).coerceIn(1, MAX_TOKENS_CAP)
        val seed = params["seed"]?.toIntOrNull()

        if (!inflight.tryAcquire()) {
            rejected.incrementAndGet()
            return json("429 Too Many Requests",
                """{"error":"busy","detail":"$MAX_INFLIGHT requests already in flight","retry_after_ms":1000}""",
                mapOf("Retry-After" to "1"))
        }
        queued.incrementAndGet()
        try {
            val text = try {
                kotlinx.coroutines.runBlocking { generate(prompt, maxTokens, seed) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                return json("504 Gateway Timeout",
                    """{"error":"timeout","detail":"inference exceeded ${REQUEST_TIMEOUT_MS / 1000}s"}""")
            } catch (e: Exception) {
                return json("500 Internal Server Error",
                    """{"error":"inference_failed","detail":${jsonString(e.message ?: "unknown")}}""")
            }
            val engineName = if (engineTag != null) "ollama" else "markov-fallback"
            return json("200 OK",
                """{"model":"$engineName","backend":"$engineBackend","prompt":${jsonString(prompt)},"text":${jsonString(text)},"max_tokens":$maxTokens,"seed":${seed ?: "null"},"served_from":"this device"}""")
        } finally {
            queued.decrementAndGet()
            inflight.release()
        }
    }

    private suspend fun generate(prompt: String, maxTokens: Int, seed: Int?): String {
        val tag = engineTag
        if (tag != null) {
            return withTimeout(REQUEST_TIMEOUT_MS) {
                engineMutex.withLock {
                    OllamaClient.generate(tag, prompt, maxTokens, seed)
                }
            }
        }
        return fallbackModel.generate(prompt, maxTokens.coerceAtMost(200), seed?.toLong())
    }

    private fun modelJson(): String {
        val engineName = if (engineTag != null) "ollama" else "markov-fallback"
        val tag = engineTag?.let { jsonString(it) } ?: "null"
        return """{"id":"$engineName","backend":"$engineBackend","model_tag":$tag,"parameters":${if (engineTag != null) "model-dependent" else fallbackModel.stateCount},"vocab":${if (engineTag != null) "model-dependent" else fallbackModel.vocabSize},"note":"run Ollama and pull a model to upgrade to a real LLM"}"""
    }

    private fun healthJson(): String {
        val uptime = (System.currentTimeMillis() - startedAt) / 1000
        val engineName = if (engineTag != null) "ollama" else "markov-fallback"
        return """{"status":"ok","engine":"$engineName","backend":"$engineBackend","uptime_seconds":$uptime,"requests":${requests.get()},"inflight":${queued.get()},"rejected":${rejected.get()},"max_inflight":$MAX_INFLIGHT}"""
    }

    private fun json(status: String, body: String, headers: Map<String, String> = emptyMap()) =
        HttpResponse(status, "application/json", body, headers)

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""

    private fun urlDecode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    private fun indexHtml(): String = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>On-device model · Portal</title>
<style>body{background:#080f1d;color:#f0f5fc;font-family:system-ui,sans-serif;max-width:640px;margin:60px auto;padding:0 24px;line-height:1.6}
h1{font-size:32px}code{background:rgba(100,220,236,.12);color:#64dcec;padding:2px 8px;border-radius:6px;font-size:14px}
a{color:#64dcec}.card{background:#111e30;border-radius:14px;padding:20px;margin:14px 0}
input,button{font:inherit;padding:10px 14px;border-radius:10px;border:1px solid #1b2d42;background:#0a0e1e;color:#f0f5fc;width:100%;box-sizing:border-box}
button{background:#64dcec;color:#080f1d;font-weight:700;border:none;cursor:pointer;margin-top:10px}
#out{background:#0a0e1e;border-radius:10px;padding:14px;margin-top:14px;font-family:monospace;font-size:13px;white-space:pre-wrap;display:none}</style></head>
<body><h1>On-device model</h1>
<p>A language model running <b>inside this app</b>. Your prompt crosses a Portal relay, inference happens on the device, and the completion comes back — no cloud.</p>
<div class="card"><b>Try it</b><br>
<input id="p" placeholder="prompt — e.g. explain portals" value="explain portals">
<button onclick="go()">Generate</button>
<div id="out"></div></div>
<div class="card"><b>Endpoints</b><br>
<a href="/v1/generate?prompt=explain%20portals">/v1/generate?prompt=…</a> — completion<br>
<a href="/v1/model">/v1/model</a> — model card<br>
<a href="/v1/health">/v1/health</a> — liveness</div>
<p style="color:#a7b8ce;font-size:14px">Engine: <code>Ollama</code> when the daemon is running and a model is pulled; a tiny embedded Markov chain otherwise. Same API either way.</p>
<script>
async function go(){
  const out = document.getElementById('out');
  out.style.display='block'; out.textContent='…';
  const r = await fetch('/v1/generate?max_tokens=512&prompt='+encodeURIComponent(document.getElementById('p').value));
  const j = await r.json();
  out.textContent = j.text + '\n\n— ' + j.model + ' (' + j.backend + ') · ' + j.served_from;
}
</script></body></html>"""

    // ---- fallback model ------------------------------------------------------

    /** Order-2 Markov chain over an embedded corpus. */
    private class MarkovModel(corpus: String) {
        private val chain: Map<Pair<String, String>, List<String>>
        val vocabSize: Int
        val stateCount: Int get() = chain.size

        init {
            val words = TOKENIZER.findAll(corpus.lowercase()).map { it.value }.toList()
            vocabSize = words.toSet().size
            val map = mutableMapOf<Pair<String, String>, MutableList<String>>()
            for (i in 0 until words.size - 2) {
                map.getOrPut(words[i] to words[i + 1]) { mutableListOf() }.add(words[i + 2])
            }
            chain = map
        }

        fun generate(prompt: String, maxTokens: Int, seed: Long?): String {
            val rng = seed?.let { Random(it) } ?: Random.Default
            val promptWords = TOKENIZER.findAll(prompt.lowercase()).map { it.value }.toList()
            var state = promptWords.takeLast(2).let {
                if (it.size == 2) it[0] to it[1] else null
            } ?: chain.keys.random(rng)
            val out = mutableListOf<String>()
            repeat(maxTokens) {
                val next = chain[state]?.random(rng) ?: return@repeat
                out.add(next)
                state = state.second to next
            }
            return out.joinToString(" ").ifBlank { "(no continuation — try a different prompt)" }
        }

        private companion object {
            val TOKENIZER = Regex("[a-z']+|[.,!?;:]")
        }
    }

    private const val CORPUS = """
        Portal turns any device into a public server. Your phone, your laptop,
        a Raspberry Pi behind a NAT — all of them can serve a website, an API,
        or a game server without a cloud account, a public IP, or port
        forwarding. The tunnel connects your device to a relay. The relay
        gives you a public address. Visitors load your site through that
        address, and the traffic flows back to your device. You stay in
        control: stop the tunnel and the address dies instantly. Rotate
        relays, update metadata, or hide from the public directory — all
        live, no restart. The SDK is one Kotlin Multiplatform library. The
        same PortalClient API works on Android, iOS, and desktop. The native
        engine handles the transport; the SDK handles the lifecycle. Serve a
        static site, proxy a local HTTP server, forward raw TCP or UDP. A
        Minecraft server, a webhook receiver, a demo API — all from the
        device in your pocket. No infrastructure. No DNS. No TLS certs. The
        relay handles the public endpoint; you handle the content.
    """
}
