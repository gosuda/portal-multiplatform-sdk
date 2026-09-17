package org.gosuda.portal.sample.content.ondevice

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.sample.content.PublishableContent
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * On-device LLM served over HTTP, exposed through `target_addr`.
 *
 * Follows Google's official LiteRT-LM Android tutorial: an `Engine` is
 * initialized on a background thread with a cascading backend fallback
 * (GPU → CPU → text-only CPU), and each request runs through a
 * `Conversation` + `sendMessageAsync` flow.
 *
 * Model file: drop a `.litertlm` model (e.g. Gemma 3n E2B it) into
 * `Android/data/org.gosuda.portal.sample/files/models/` on the device.
 * When no model file is present, a tiny embedded Markov chain answers
 * instead — the endpoint shape is identical, so the demo works out of the
 * box and upgrades to a real LLM the moment a model lands.
 *
 * Endpoints:
 * - `GET /`                      → HTML playground (prompt form)
 * - `GET /v1/generate?prompt=&max_tokens=` → JSON completion
 * - `GET /v1/model`              → JSON model card (engine, backend, path)
 * - `GET /v1/health`             → JSON liveness
 * - anything else                → JSON 404
 */
object OnDeviceModelContent : PublishableContent {
    override val id = "ondevice"
    override val title = "On-device model"
    override val summary = "LiteRT-LM LLM on this device — real inference, no cloud"
    override val detail = "target_addr → 127.0.0.1:$PORT · /v1/generate"

    const val PORT = 18080
    private const val MAX_TOKENS_CAP = 512

    private var server: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val requests = AtomicLong(0)
    @Volatile private var startedAt = 0L

    // ---- LiteRT-LM engine ---------------------------------------------------

    private var engine: Engine? = null
    private var engineBackend: String = "none"
    private var engineModelPath: String? = null
    private val engineMutex = Mutex()

    private val fallbackModel = MarkovModel(CORPUS)

    override suspend fun start(context: Context) {
        if (server != null) return
        val socket = ServerSocket(PORT)
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
        // Engine init is slow (model load + backend probe); run it off the
        // accept loop so the port is listening immediately.
        s.launch { initEngine(context) }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
        runCatching { server?.close() }
        server = null
        runCatching { engine?.close() }
        engine = null
        engineBackend = "none"
        engineModelPath = null
    }

    override fun applyTo(config: PortalConfig, context: Context): PortalConfig =
        config.copy(targetAddr = "127.0.0.1:$PORT")

    // ---- engine lifecycle ---------------------------------------------------

    private suspend fun initEngine(context: Context) = withContext(Dispatchers.IO) {
        val modelFile = findModelFile(context) ?: return@withContext

        // OOM guard: refuse to load when the device can't spare the model
        // plus KV-cache headroom. The fallback model keeps serving.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val needed = modelFile.length() * 3 / 2
        if (mem.lowMemory || mem.availMem < needed) {
            engineBackend = "skipped:low-memory"
            return@withContext
        }

        engineMutex.withLock {
            if (engine != null) return@withLock
            // Cascading fallback per the official tutorial: GPU → CPU.
            // maxNumTokens caps the KV cache; threadCount caps CPU workers —
            // both keep a small model from evicting the rest of the system.
            val candidates = listOf(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.GPU(),
                    maxNumTokens = 1024,
                    cacheDir = context.cacheDir.absolutePath
                ),
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(threadCount = 2),
                    maxNumTokens = 1024,
                    cacheDir = context.cacheDir.absolutePath
                ),
            )
            for (config in candidates) {
                try {
                    val e = Engine(config)
                    e.initialize()
                    engine = e
                    engineBackend = config.backend.name
                    engineModelPath = modelFile.absolutePath
                    return@withLock
                } catch (_: Throwable) {
                    // Try the next backend.
                }
            }
            engineBackend = "init-failed"
        }
    }

    private fun findModelFile(context: Context): File? {
        val file = ModelDownload.modelFile(context)
        return if (file.exists() && file.length() > 0) file else null
    }

    // ---- HTTP handling ------------------------------------------------------

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
            val (status, contentType, body) = route(path, query)
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            writer.write("HTTP/1.1 $status\r\n")
            writer.write("Content-Type: $contentType\r\n")
            writer.write("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.write(body)
            writer.flush()
        }
    }

    private fun route(path: String, query: String): Triple<String, String, String> = when (path) {
        "/" -> Triple("200 OK", "text/html; charset=utf-8", indexHtml())
        "/v1/generate" -> json("200 OK", generateJsonBlocking(query))
        "/v1/model" -> json("200 OK", modelJson())
        "/v1/health" -> json("200 OK", healthJson())
        else -> json("404 Not Found",
            """{"error":"not_found","path":${jsonString(path)},"endpoints":["/","/v1/generate","/v1/model","/v1/health"]}""")
    }

    private fun generateJsonBlocking(query: String): String {
        val params = query.split('&')
            .filter { it.contains('=') }
            .associate {
                val (k, v) = it.split('=', limit = 2)
                urlDecode(k) to urlDecode(v)
            }
        val prompt = params["prompt"].orEmpty().ifBlank { "Portal" }
        val maxTokens = (params["max_tokens"]?.toIntOrNull() ?: 128).coerceIn(1, MAX_TOKENS_CAP)
        val seed = params["seed"]?.toIntOrNull()

        val text = runCatching {
            kotlinx.coroutines.runBlocking { generate(prompt, maxTokens, seed) }
        }.getOrElse { "(inference failed: ${it.message})" }

        val engineName = if (engine != null) "litert-lm" else "markov-fallback"
        return """{"model":"$engineName","backend":"$engineBackend","prompt":${jsonString(prompt)},"text":${jsonString(text)},"max_tokens":$maxTokens,"seed":${seed ?: "null"},"served_from":"this device"}"""
    }

    private suspend fun generate(prompt: String, maxTokens: Int, seed: Int?): String {
        val e = engine
        if (e != null) {
            // Serialized + time-boxed: one request at a time, never a hang.
            return withTimeout(120_000) {
                engineMutex.withLock {
                    val config = ConversationConfig(
                        samplerConfig = seed?.let { SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8, seed = it) }
                    )
                    e.createConversation(config).use { conversation ->
                        conversation.sendMessageAsync(
                            Contents.of(prompt),
                            maxOutputToken = maxTokens
                        ).fold(StringBuilder()) { acc, msg -> acc.append(msg.toString()) }.toString()
                    }
                }
            }
        }
        return fallbackModel.generate(prompt, maxTokens.coerceAtMost(200), seed?.toLong())
    }

    private fun modelJson(): String {
        val engineName = if (engine != null) "litert-lm" else "markov-fallback"
        val path = engineModelPath?.let { jsonString(it) } ?: "null"
        return """{"id":"$engineName","backend":"$engineBackend","model_path":$path,"parameters":${if (engine != null) "model-dependent" else fallbackModel.stateCount},"vocab":${if (engine != null) "model-dependent" else fallbackModel.vocabSize},"note":"drop a .litertlm model into Android/data/org.gosuda.portal.sample/files/models/ to upgrade to a real LLM"}"""
    }

    private fun healthJson(): String {
        val uptime = (System.currentTimeMillis() - startedAt) / 1000
        val engineName = if (engine != null) "litert-lm" else "markov-fallback"
        return """{"status":"ok","engine":"$engineName","backend":"$engineBackend","uptime_seconds":$uptime,"requests":${requests.get()}}"""
    }

    private fun json(status: String, body: String) = Triple(status, "application/json", body)

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
<p style="color:#a7b8ce;font-size:14px">Engine: <code>LiteRT-LM</code> when a <code>.litertlm</code> model is installed; a tiny embedded Markov chain otherwise. Same API either way.</p>
<script>
async function go(){
  const out = document.getElementById('out');
  out.style.display='block'; out.textContent='…';
  const r = await fetch('/v1/generate?prompt='+encodeURIComponent(document.getElementById('p').value));
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
