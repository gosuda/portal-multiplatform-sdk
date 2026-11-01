package org.gosuda.portal.sample

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.gosuda.portal.PortalConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Sample-only, loopback-only services. Logs never include request bodies, query strings or headers. */
class LocalDemo private constructor() : AutoCloseable {
    data class Activity(val count: Long = 0, val lines: List<String> = emptyList())
    private val mutableActivity = MutableStateFlow(Activity())
    val activity: StateFlow<Activity> = mutableActivity
    lateinit var config: PortalConfig
        private set
    private val closed = AtomicBoolean(false)
    private val httpServers = mutableListOf<HttpServer>()
    private var tcp: ServerSocket? = null
    @Volatile private var connection: Socket? = null
    private var udp: DatagramSocket? = null

    @Synchronized
    private fun record(line: String) {
        val old = mutableActivity.value
        mutableActivity.value = Activity(old.count + 1, (old.lines + line).takeLast(40))
    }

    private fun http(role: String): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                val input = exchange.requestBody
                val buffer = ByteArray(1024)
                var received = 0
                while (received <= 16_384) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 16_385 - received))
                    if (count < 0) break
                    received += count
                }
                if (received > 16_384) {
                    record("HTTP request rejected: body exceeds 16 KiB")
                    exchange.sendResponseHeaders(413, -1)
                    return@createContext
                }
                val path = exchange.requestURI.rawPath.take(160)
                record("HTTP ${exchange.requestMethod.take(16)} $path [$role]")
                val api = role == "api" || (role == "combined" && path.startsWith("/api/"))
                val body = if (api) "{\"demo\":\"portal-api\",\"ok\":true}\n"
                    else "Portal webhook receiver: ${exchange.requestMethod.take(16)} accepted\n"
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", if (api) "application/json" else "text/plain; charset=utf-8")
                exchange.responseHeaders.set("Connection", "close")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { output -> output.write(bytes) }
            }
        }
        httpServers += server
        server.start()
        return "127.0.0.1:${server.address.port}"
    }

    private fun tcp(): String {
        val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        tcp = listener
        thread(name = "portal-demo-tcp", isDaemon = true) {
            val bytes = ByteArray(4096)
            try {
                while (!closed.get()) {
                    listener.accept().use { socket ->
                        connection = socket
                        socket.soTimeout = 5_000
                        try {
                            while (!closed.get()) {
                                val count = socket.getInputStream().read(bytes)
                                if (count < 0) break
                                socket.getOutputStream().write(bytes, 0, count)
                                socket.getOutputStream().flush()
                                record("TCP echoed $count bytes")
                            }
                        } catch (_: java.io.IOException) {
                            // A peer disconnect/idle timeout only ends this connection.
                        } finally { connection = null }
                    }
                }
            } catch (failure: java.io.IOException) {
                if (!closed.get()) record("TCP listener failed: ${failure.javaClass.simpleName}")
            }
        }
        return "127.0.0.1:${listener.localPort}"
    }

    private fun udp(): String {
        val socket = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        udp = socket
        thread(name = "portal-demo-udp", isDaemon = true) {
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                while (!closed.get()) {
                    packet.length = buffer.size
                    socket.receive(packet)
                    socket.send(packet)
                    record("UDP echoed ${packet.length} bytes")
                }
            } catch (failure: java.io.IOException) {
                if (!closed.get()) record("UDP listener failed: ${failure.javaClass.simpleName}")
            }
        }
        return "127.0.0.1:${socket.localPort}"
    }

    override fun close() {
        closed.set(true)
        httpServers.forEach { it.stop(0) }
        connection?.close()
        tcp?.close()
        udp?.close()
    }

    companion object {
        fun prepare(request: WorkbenchRequest): LocalDemo {
            val demo = LocalDemo()
            try {
                val resolved = when {
                    request.mode == WorkbenchMode.STATIC && request.directory.isBlank() -> {
                        val resources = checkNotNull(System.getProperty("compose.application.resources.dir")) {
                            "Run with Gradle or the packaged launcher to locate bundled games."
                        }
                        val directory = Path.of(resources, "site")
                        check(Files.isDirectory(directory)) { "Bundled arcade directory is missing: $directory" }
                        request.copy(directory = directory.toString())
                    }
                    !request.builtIn -> request
                    request.mode == WorkbenchMode.HTTP -> request.copy(target = demo.http("combined"))
                    request.mode == WorkbenchMode.ROUTES -> request.copy(
                        firstPrefix = "/api/", firstUpstream = "http://${demo.http("api")}",
                        secondPrefix = "/", secondUpstream = "http://${demo.http("webhook")}")
                    request.mode == WorkbenchMode.TCP -> request.copy(target = demo.tcp())
                    request.mode == WorkbenchMode.UDP -> request.copy(target = demo.udp())
                    else -> request
                }
                demo.config = resolved.config()
                return demo
            } catch (failure: Exception) {
                demo.close()
                throw failure
            }
        }
    }
}
