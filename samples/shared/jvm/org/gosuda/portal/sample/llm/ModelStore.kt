package org.gosuda.portal.sample.llm

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

data class ModelSpec(
    val id: String,
    val title: String,
    val summary: String,
    val parameters: String,
    val quantization: String,
    val languages: String,
    val downloadBytes: Long,
    val url: String,
    val sha256: String,
    val license: String,
    val source: String,
) {
    val downloadMiB: Long get() = (downloadBytes + 1_048_575) / 1_048_576
}

enum class DownloadPhase { IDLE, DOWNLOADING, VERIFYING, READY, CANCELLED, FAILED }

data class ModelDownloadState(
    val modelId: String? = null,
    val phase: DownloadPhase = DownloadPhase.IDLE,
    val receivedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
)

/** Sample-only artifact cache. Nothing downloads until the user calls download. */
class ModelStore(val directory: File, catalogJson: String) {
    val models: List<ModelSpec> = Json.parseToJsonElement(catalogJson).jsonArray.map { entry ->
        val value = entry.jsonObject
        fun text(key: String): String = value.getValue(key).jsonPrimitive.content
        ModelSpec(text("id"), text("title"), text("summary"), text("parameters"),
            text("quantization"), text("languages"), value.getValue("downloadBytes").jsonPrimitive.long,
            text("url"), text("sha256"), text("license"), text("source")).also {
            require(CACHE_ID.matches(it.id) && SHA256.matches(it.sha256)) {
                "Invalid model cache key"
            }
            require(it.downloadBytes > 0 && URL(it.url).protocol == "https") { "Invalid model artifact" }
        }
    }.also { require(it.isNotEmpty() && it.map(ModelSpec::id).distinct().size == it.size) { "Invalid model catalog" } }

    private val mutableProgress = MutableStateFlow(ModelDownloadState())
    val progress: StateFlow<ModelDownloadState> = mutableProgress.asStateFlow()
    private val downloadGate = Mutex()
    @Volatile private var downloadJob: Job? = null
    @Volatile private var connection: HttpURLConnection? = null

    fun model(id: String): ModelSpec = models.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Unknown catalog model")

    // Only this class writes final hash-keyed files, after complete size+SHA verification.
    // They are immutable app-private artifacts, not a shared writable download directory.
    fun cachedFile(id: String): File? {
        val spec = model(id)
        return artifact(spec).takeIf { it.isFile && it.length() == spec.downloadBytes }
    }

    suspend fun download(id: String) {
        check(downloadGate.tryLock()) { "A model download is already running" }
        try {
            withContext(Dispatchers.IO) {
                val spec = model(id)
                cachedFile(id)?.let {
                    mutableProgress.value = ModelDownloadState(id, DownloadPhase.READY, spec.downloadBytes, spec.downloadBytes)
                    return@withContext
                }
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create model storage" }
                check(directory.usableSpace >= spec.downloadBytes) { "Not enough space for this model" }
                val partial = File(directory, "${spec.id}-${spec.sha256}.part")
                downloadJob = currentCoroutineContext()[Job]
                var received = 0L
                var primaryFailure: Throwable? = null
                try {
                    mutableProgress.value = ModelDownloadState(id, DownloadPhase.DOWNLOADING, 0, spec.downloadBytes)
                    val request = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 20_000
                        setRequestProperty("Accept-Encoding", "identity")
                    }
                    connection = request
                    check(request.responseCode in 200..299) { "Model download returned HTTP ${request.responseCode}" }
                    check(request.url.protocol == "https") { "Model download redirected to insecure transport" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var lastUpdate = 0L
                    request.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                check(received <= spec.downloadBytes) { "Model exceeded the catalog size" }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                val now = System.nanoTime()
                                if (now - lastUpdate >= 100_000_000L) {
                                    mutableProgress.value = ModelDownloadState(id, DownloadPhase.DOWNLOADING, received, spec.downloadBytes)
                                    lastUpdate = now
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    mutableProgress.value = ModelDownloadState(id, DownloadPhase.VERIFYING, received, spec.downloadBytes)
                    check(received == spec.downloadBytes) { "Incomplete model download" }
                    val hash = digest.digest()
                    val actual = CharArray(hash.size * 2)
                    for (index in hash.indices) {
                        val byte = hash[index].toInt() and 0xff
                        actual[index * 2] = HEX[byte ushr 4]
                        actual[index * 2 + 1] = HEX[byte and 15]
                    }
                    check(String(actual) == spec.sha256) { "Model checksum did not match the pinned artifact" }
                    currentCoroutineContext().ensureActive()
                    Files.move(partial.toPath(), artifact(spec).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    mutableProgress.value = ModelDownloadState(id, DownloadPhase.READY, received, spec.downloadBytes)
                } catch (failure: Exception) {
                    val cancelled = failure is CancellationException || !currentCoroutineContext().isActive
                    mutableProgress.value = ModelDownloadState(id,
                        if (cancelled) DownloadPhase.CANCELLED else DownloadPhase.FAILED,
                        received, spec.downloadBytes, if (cancelled) null else failure.message)
                    val reported = if (cancelled) CancellationException("Model download cancelled", failure) else failure
                    primaryFailure = reported
                    throw reported
                } finally {
                    connection?.disconnect()
                    connection = null
                    downloadJob = null
                    if (partial.exists() && !partial.delete()) {
                        val cleanup = IllegalStateException("Could not remove incomplete model download")
                        primaryFailure?.addSuppressed(cleanup) ?: throw cleanup
                    }
                }
            }
        } finally {
            downloadGate.unlock()
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        connection?.disconnect()
    }

    fun delete(id: String) {
        check(!downloadGate.isLocked) { "Wait for the model download to finish" }
        val file = artifact(model(id))
        check(!file.exists() || file.delete()) { "Could not delete the downloaded model" }
        mutableProgress.value = ModelDownloadState()
    }

    private fun artifact(spec: ModelSpec): File = File(directory, "${spec.id}-${spec.sha256}.gguf")

    private companion object {
        val CACHE_ID = Regex("[a-z0-9-]+")
        val SHA256 = Regex("[0-9a-f]{64}")
        const val HEX = "0123456789abcdef"
    }
}
