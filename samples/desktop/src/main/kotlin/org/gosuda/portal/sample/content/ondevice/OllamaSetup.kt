package org.gosuda.portal.sample.content.ondevice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gosuda.portal.sample.content.SampleEnv
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.math.max

/**
 * In-app Ollama setup for the desktop sample — the analogue of Android's
 * bundled LiteRT-LM engine. Downloads the official Ollama archive for the
 * host OS into the app directory, extracts the `ollama` binary, and runs
 * `ollama serve` as a managed child process with an isolated model store
 * and captured logs.
 *
 * If a system Ollama is already answering on 127.0.0.1:11434 it is used
 * as-is; the managed daemon only starts when nothing is listening.
 *
 * Lifecycle states mirror the Android model-download flow so the UI can
 * show progress and retry.
 */
object OllamaSetup {

    sealed interface State {
        /** Nothing installed; no daemon detected. */
        data object NotInstalled : State
        /** Downloading the archive. [progress] is 0..1, -1 when unknown. */
        data class Downloading(val progress: Float, val detail: String) : State
        /** Archive downloaded; extracting / starting the daemon. */
        data object Installing : State
        /** Managed daemon is up (or a system daemon was adopted). */
        data object Running : State
        /** Something failed; [message] is user-readable. */
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.NotInstalled)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Last daemon log lines, for the UI's diagnostics surface. */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var daemon: Process? = null
    private var logPump: Thread? = null

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val osName = (System.getProperty("os.name") ?: "").lowercase()
    private val osArch = (System.getProperty("os.arch") ?: "").lowercase()

    private val installDir: File get() = File(SampleEnv.appDir, "ollama")
    private val binDir: File get() = File(installDir, "bin")
    private val modelsDir: File get() = File(installDir, "models")
    private val logFile: File get() = File(installDir, "ollama.log")

    /** The managed binary path for this OS, or null if unsupported. */
    val binary: File?
        get() = when {
            osName.startsWith("linux") -> File(binDir, "ollama")
            osName.startsWith("mac") || osName.startsWith("darwin") ->
                File(binDir, "ollama")
            osName.startsWith("windows") -> File(binDir, "ollama.exe")
            else -> null
        }

    val isSupported: Boolean get() = binary != null &&
        !(osName.startsWith("linux") && !(osArch == "x86_64" || osArch == "amd64" || osArch == "aarch64" || osArch == "arm64"))

    /** Download URL for the host platform. */
    private fun downloadUrl(): String? = when {
        osName.startsWith("linux") && (osArch == "x86_64" || osArch == "amd64") ->
            "https://ollama.com/download/ollama-linux-amd64.tgz"
        osName.startsWith("linux") && (osArch == "aarch64" || osArch == "arm64") ->
            "https://ollama.com/download/ollama-linux-arm64.tgz"
        osName.startsWith("mac") || osName.startsWith("darwin") ->
            "https://ollama.com/download/ollama-darwin.zip"
        osName.startsWith("windows") ->
            "https://ollama.com/download/ollama-windows-amd64.zip"
        else -> null
    }

    /** True when any Ollama daemon (system or managed) answers. */
    suspend fun daemonUp(): Boolean = OllamaClient.isRunning()

    /**
     * Ensures a usable daemon: adopts a running system daemon or starts the
     * managed one if installed. Never downloads — install is explicit via
     * [install]. Safe to call repeatedly.
     */
    fun ensureRunning() {
        scope.launch {
            if (OllamaClient.isRunning()) {
                _state.value = State.Running
                return@launch
            }
            val bin = binary
            if (bin != null && bin.canExecute()) {
                startDaemon(bin)
            } else if (_state.value == State.Running) {
                _state.value = State.NotInstalled
            }
        }
    }

    /** Full install: download → extract → start daemon. */
    fun install() {
        scope.launch {
            val url = downloadUrl()
            val bin = binary
            if (url == null || bin == null) {
                _state.value = State.Failed("Ollama auto-install is not supported on $osName/$osArch")
                return@launch
            }
            try {
                val archive = download(url)
                extract(archive, bin)
                archive.delete()
                startDaemon(bin)
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "install failed")
            }
        }
    }

    /** Stops the managed daemon (a system daemon is left alone). */
    fun stopDaemon() {
        daemon?.let { p ->
            p.destroy()
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        daemon = null
        logPump?.interrupt()
        logPump = null
        if (_state.value == State.Running) _state.value = State.NotInstalled
    }

    // ---- download ----------------------------------------------------------------

    private suspend fun download(url: String): File = withContext(Dispatchers.IO) {
        installDir.mkdirs()
        val dest = File(installDir, url.substringAfterLast('/'))
        _state.value = State.Downloading(0f, "connecting")
        val req = HttpRequest.newBuilder(URI(url)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
        if (resp.statusCode() != 200) throw IllegalStateException("download failed: HTTP ${resp.statusCode()}")
        val total = resp.headers().firstValueAsLong("Content-Length").orElse(-1L)
        resp.body().use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(1 shl 20)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport > (4 shl 20) || n < buf.size) {
                        lastReport = done
                        val pct = if (total > 0) done.toFloat() / total else -1f
                        _state.value = State.Downloading(pct,
                            "${done / (1 shl 20)} MB${if (total > 0) " / ${total / (1 shl 20)} MB" else ""}")
                    }
                }
            }
        }
        dest
    }

    // ---- extract -----------------------------------------------------------------

    private suspend fun extract(archive: File, bin: File) = withContext(Dispatchers.IO) {
        _state.value = State.Installing
        binDir.mkdirs()
        when {
            archive.name.endsWith(".zip") -> extractZip(archive, bin)
            archive.name.endsWith(".tgz") || archive.name.endsWith(".tar.gz") ->
                extractTgz(archive, bin)
            else -> throw IllegalStateException("unknown archive: ${archive.name}")
        }
        bin.setExecutable(true)
        if (!bin.canExecute()) throw IllegalStateException("ollama binary missing after extract")
    }

    private fun extractZip(zip: File, bin: File) {
        ZipFile(zip).use { z ->
            // macOS zip contains Ollama.app; the CLI lives inside it.
            val entry = z.entries().asSequence().firstOrNull {
                it.name.endsWith("/ollama") || it.name == "ollama" || it.name == "ollama.exe"
            } ?: throw IllegalStateException("ollama binary not in zip")
            z.getInputStream(entry).use { input ->
                bin.outputStream().use { input.copyTo(it) }
            }
        }
    }

    private fun extractTgz(archive: File, bin: File) {
        // No zip4j/tar dependency: shell out to tar, which exists on every
        // supported Linux/macOS host.
        val proc = ProcessBuilder("tar", "-xzf", archive.absolutePath, "-C", installDir.absolutePath)
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) throw IllegalStateException("tar failed: $out")
        // The tgz lays binaries under bin/ already; nothing else to move.
        if (!bin.exists()) {
            // Some archives put it at top level.
            val alt = File(installDir, "ollama")
            if (alt.exists()) alt.renameTo(bin)
        }
    }

    // ---- daemon ------------------------------------------------------------------

    private suspend fun startDaemon(bin: File) = withContext(Dispatchers.IO) {
        if (OllamaClient.isRunning()) { _state.value = State.Running; return@withContext }
        _state.value = State.Installing
        modelsDir.mkdirs()
        logFile.parentFile?.mkdirs()
        val pb = ProcessBuilder(bin.absolutePath, "serve")
        pb.environment()["OLLAMA_MODELS"] = modelsDir.absolutePath
        pb.environment()["OLLAMA_HOST"] = "127.0.0.1:11434"
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        val proc = pb.start()
        daemon = proc
        pumpLog()
        // Wait for the API to come up (model dir scan can take a moment).
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                _state.value = State.Failed("ollama serve exited (${proc.exitValue()}) — see log")
                return@withContext
            }
            if (OllamaClient.isRunning()) { _state.value = State.Running; return@withContext }
            delay(300)
        }
        _state.value = State.Failed("ollama serve did not come up in 20s")
    }

    private fun pumpLog() {
        // Output is redirected to logFile; tail it so the UI can show the
        // daemon's own diagnostics.
        logPump = Thread {
            val lines = ArrayDeque<String>(64)
            try {
                var offset = max(0L, logFile.length() - 8192)
                while (!Thread.currentThread().isInterrupted) {
                    val len = logFile.length()
                    if (len > offset) {
                        logFile.inputStream().use { input ->
                            input.skip(offset)
                            val text = input.bufferedReader().readText()
                            offset = len
                            text.lines().filter { it.isNotBlank() }.forEach { line ->
                                synchronized(lines) {
                                    lines.addLast(line)
                                    while (lines.size > 64) lines.removeFirst()
                                    _log.value = lines.toList()
                                }
                            }
                        }
                    }
                    Thread.sleep(500)
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
    }
}
