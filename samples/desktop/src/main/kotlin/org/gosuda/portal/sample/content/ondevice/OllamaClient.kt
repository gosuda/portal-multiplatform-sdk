package org.gosuda.portal.sample.content.ondevice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal client for a locally-running Ollama daemon (`http://127.0.0.1:11434`).
 *
 * Ollama is the desktop analogue of Android's LiteRT-LM: a local inference
 * engine with automatic GPU use, a model catalog, and managed downloads.
 * The sample treats it as an optional upgrade — when the daemon or a model
 * is absent, the embedded Markov fallback keeps the endpoint working.
 */
object OllamaClient {
    private const val BASE = "http://127.0.0.1:11434"
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    /** True when the daemon answers `/api/version`. */
    suspend fun isRunning(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resp = http.send(
                HttpRequest.newBuilder(URI("$BASE/api/version")).GET()
                    .timeout(Duration.ofSeconds(3)).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            resp.statusCode() == 200
        }.getOrDefault(false)
    }

    /** Names of installed models from `/api/tags`. */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = http.send(
                HttpRequest.newBuilder(URI("$BASE/api/tags")).GET()
                    .timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (resp.statusCode() != 200) return@runCatching emptyList<String>()
            // Extract "name":"..." occurrences — the tags payload is flat.
            Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(resp.body()).map { it.groupValues[1] }.toList()
        }.getOrDefault(emptyList())
    }

    /**
     * One-shot generation via `/api/generate` (stream=false). Returns the
     * completion text. Throws on non-200 or a missing `response` field.
     */
    suspend fun generate(model: String, prompt: String, maxTokens: Int, seed: Int?): String =
        withContext(Dispatchers.IO) {
            val options = buildString {
                append("\"num_predict\":$maxTokens")
                seed?.let { append(",\"seed\":$it") }
            }
            val body = """{"model":${jsonString(model)},"prompt":${jsonString(prompt)},"stream":false,"options":{$options}}"""
            val resp = http.send(
                HttpRequest.newBuilder(URI("$BASE/api/generate"))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120)).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (resp.statusCode() != 200) {
                throw IllegalStateException("ollama generate failed: ${resp.statusCode()} ${resp.body().take(200)}")
            }
            Regex("\"response\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(resp.body())?.groupValues?.get(1)?.let { unescapeJson(it) }
                ?: throw IllegalStateException("ollama returned no response field")
        }

    /**
     * Pulls [model], invoking [onProgress] with (completedBytes, totalBytes)
     * as the streamed status lines arrive. Throws on a non-200 or an error
     * status line.
     */
    suspend fun pull(model: String, onProgress: (done: Long, total: Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val resp = http.send(
                HttpRequest.newBuilder(URI("$BASE/api/pull"))
                    .POST(HttpRequest.BodyPublishers.ofString("""{"name":${jsonString(model)},"stream":true}"""))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(30)).build(),
                HttpResponse.BodyHandlers.ofLines()
            )
            if (resp.statusCode() != 200) {
                throw IllegalStateException("ollama pull failed: ${resp.statusCode()}")
            }
            resp.body().forEach { line ->
                if (line.contains("\"error\"")) {
                    val msg = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                        .find(line)?.groupValues?.get(1) ?: "pull error"
                    throw IllegalStateException(unescapeJson(msg))
                }
                val completed = Regex("\"completed\"\\s*:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                val total = Regex("\"total\"\\s*:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                if (completed != null && total != null && total > 0) onProgress(completed, total)
            }
        }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun unescapeJson(value: String): String =
        value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
}
