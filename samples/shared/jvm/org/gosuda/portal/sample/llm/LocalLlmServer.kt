package org.gosuda.portal.sample.llm

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A bounded, sample-only server. Portal exposes this loopback HTTP upstream. */
class LocalLlmServer(
    private val model: ModelSpec,
    modelFile: File,
    password: String,
    asset: (String) -> ByteArray,
) : AutoCloseable {
    private val expectedAuthorization = LlmPassword.authorization(password.also {
        require(LlmPassword.isValid(it)) { "Use a password of 12–128 Unicode characters" }
    }).toByteArray(Charsets.US_ASCII)
    private val staticFiles = mapOf(
        "/" to ("text/html; charset=utf-8" to asset("llm/index.html")),
        "/llm.css" to ("text/css; charset=utf-8" to asset("llm/llm.css")),
        "/llm.js" to ("text/javascript; charset=utf-8" to asset("llm/llm.js")),
    )
    private val closed = AtomicBoolean(false)
    private val generating = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(4), { task -> Thread(task, "portal-local-llm-http").apply { isDaemon = true } })
    private val listener = ServerSocket()
    @Volatile private var handle = 0L
    private val acceptThread: Thread
    val address: String

    init {
        try {
            NativeLlm.load()
            handle = NativeLlm.open(modelFile.absolutePath.toByteArray(Charsets.UTF_8))
            check(handle != 0L) { "Native model load returned no handle" }
            listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
            address = "127.0.0.1:${listener.localPort}"
            acceptThread = Thread({ acceptConnections() }, "portal-local-llm-accept").apply {
                isDaemon = true
                start()
            }
        } catch (failure: Exception) {
            listener.close()
            workers.shutdownNow()
            if (handle != 0L) NativeLlm.close(handle)
            throw failure
        }
    }


    private fun acceptConnections() {
        while (!closed.get()) {
            val socket = try { listener.accept() } catch (failure: SocketException) {
                if (!closed.get()) throw failure
                break
            }
            sockets.add(socket)
            if (closed.get()) {
                socket.close()
                sockets.remove(socket)
                break
            }
            try {
                workers.execute {
                    try {
                        socket.use { connection ->
                            connection.soTimeout = 10_000
                            serve(connection)
                        }
                    } catch (_: IOException) {
                        // Client disconnect/owner close: no prompt or key logging.
                    } finally {
                        sockets.remove(socket)
                    }
                }
            } catch (_: RejectedExecutionException) {
                sockets.remove(socket)
                socket.close()
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream()
        val headers = ByteArrayOutputStream()
        var tail = 0
        while (headers.size() < 16 * 1024) {
            val byte = input.read()
            if (byte < 0) return
            headers.write(byte)
            tail = (tail shl 8) or byte
            if (tail == 0x0d0a0d0a) break
        }
        if (tail != 0x0d0a0d0a) return error(socket, 413, "Request headers are too large")
        val lines = headers.toString("ISO-8859-1").split("\r\n")
        val request = lines.first().split(' ')
        if (request.size != 3) return error(socket, 400, "Invalid HTTP request")
        val path = try { URI(request[1]).path } catch (_: IllegalArgumentException) {
            return error(socket, 400, "Invalid request path")
        }
        val fields = HashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val separator = line.indexOf(':')
            if (separator <= 0) return error(socket, 400, "Invalid request headers")
            val key = line.substring(0, separator).trim().lowercase()
            if (fields.put(key, line.substring(separator + 1).trim()) != null) {
                return error(socket, 400, "Duplicate request header")
            }
        }
        val static = staticFiles[path]
        if (static != null) {
            if (request[0] != "GET") return error(socket, 405, "Use GET for page assets")
            return respond(socket, 200, static.first, static.second)
        }
        if (path != "/api/status" && path != "/api/generate") return error(socket, 404, "Unknown endpoint")
        val authorization = fields["authorization"].orEmpty().toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(expectedAuthorization, authorization)) {
            return error(socket, 401, "Enter the password configured in the host app")
        }
        if (path == "/api/status") {
            if (request[0] != "GET") return error(socket, 405, "Use GET for model status")
            return json(socket, 200, buildJsonObject {
                put("model", model.title); put("local", true); put("maxTokens", 128); put("contextTokens", 1024)
            })
        }
        if (request[0] != "POST") return error(socket, 405, "Use POST for generation")
        if (fields.containsKey("transfer-encoding")) return error(socket, 400, "Send a fixed-length JSON body")
        val length = fields["content-length"]?.toIntOrNull()
            ?: return error(socket, 400, "Content-Length is required")
        if (length !in 1..16 * 1024) return error(socket, 413, "JSON body must be at most 16 KiB")
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) return error(socket, 400, "Incomplete JSON body")
            offset += count
        }
        val data = try { Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject }
            catch (_: IllegalArgumentException) { return error(socket, 400, "Invalid JSON body") }
        val prompt: String
        val limit: Int
        try {
            val value = data.getValue("prompt").jsonPrimitive
            require(value.isString)
            prompt = value.content
            val requested = data["maxTokens"]?.jsonPrimitive
            require(requested == null || !requested.isString)
            limit = if (requested == null) 96 else requireNotNull(requested.intOrNull)
            require(prompt.isNotBlank() && '\u0000' !in prompt && limit in 1..128)
        } catch (_: IllegalArgumentException) {
            return error(socket, 400, "Provide a nonblank prompt and maxTokens between 1 and 128")
        } catch (_: NoSuchElementException) {
            return error(socket, 400, "A prompt is required")
        }
        if (!generating.compareAndSet(false, true)) return error(socket, 409, "This device is already generating")
        try {
            if (closed.get() || Thread.currentThread().isInterrupted) return error(socket, 503, "Host is stopping")
            val start = System.nanoTime()
            val result = NativeLlm.generate(handle, prompt.toByteArray(Charsets.UTF_8), limit)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            json(socket, 200, buildJsonObject {
                put("text", result.toString(Charsets.UTF_8)); put("model", model.title); put("elapsedMs", elapsed)
            })
        } catch (_: IllegalArgumentException) {
            error(socket, 400, "Prompt exceeds the model context budget")
        } catch (_: IllegalStateException) {
            error(socket, 503, "Device inference could not complete; check the host app")
        } finally {
            generating.set(false)
        }
    }

    private fun error(socket: Socket, status: Int, message: String) =
        json(socket, status, buildJsonObject { put("error", message) })

    private fun json(socket: Socket, status: Int, body: JsonObject) =
        respond(socket, status, "application/json; charset=utf-8", body.toString().toByteArray(Charsets.UTF_8))

    private fun respond(socket: Socket, status: Int, type: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 409 -> "Conflict"; 413 -> "Payload Too Large"
            else -> "Service Unavailable"
        }
        val output = socket.getOutputStream()
        val security = "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'\r\n"
        output.write(("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            security + "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    @Synchronized
    override fun close() {
        closed.set(true)
        if (handle != 0L) NativeLlm.cancel(handle)
        listener.close()
        sockets.forEach { it.close() }
        sockets.clear()
        workers.shutdownNow()
        acceptThread.join(10_000)
        check(!acceptThread.isAlive && workers.awaitTermination(10, TimeUnit.SECONDS)) {
            "Local inference shutdown is incomplete; retry cleanup"
        }
        if (handle != 0L) {
            NativeLlm.close(handle)
            handle = 0
        }
    }
}
