package org.gosuda.portal.sample.content

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

/** Small sample-only listeners: bounded buffers/logs, loopback only, no request bodies logged. */
class DemoPayload(private val activity: MutableStateFlow<DemoActivity>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val servers = mutableListOf<ServerSocket>()
    private var datagrams: DatagramSocket? = null
    var secondaryAddress: String? = null
        private set

    fun start(mode: PublishMode): String {
        val loopback = InetAddress.getByName("127.0.0.1")
        activity.value = DemoActivity()
        if (mode == PublishMode.ROUTES) {
            val first = listen(PublishMode.HTTP, "api")
            secondaryAddress = listen(PublishMode.HTTP, "webhook")
            return first
        }
        if (mode == PublishMode.UDP) {
            val socket = DatagramSocket(InetSocketAddress(loopback, 0))
            datagrams = socket
            scope.launch {
                val buffer = ByteArray(4096)
                try {
                    while (!socket.isClosed) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        socket.send(DatagramPacket(packet.data, packet.length, packet.address, packet.port))
                        record("UDP echo · ${packet.length} bytes")
                    }
                } catch (e: SocketException) {
                    if (!socket.isClosed) record("UDP listener error: ${e.javaClass.simpleName}", counted = false)
                } catch (e: Exception) {
                    record("UDP listener error: ${e.javaClass.simpleName}", counted = false)
                }
            }
            return "127.0.0.1:${socket.localPort}"
        }
        return listen(mode)
    }

    private fun listen(mode: PublishMode, role: String? = null): String {
        val loopback = InetAddress.getByName("127.0.0.1")
        val listener = ServerSocket(0, 4, loopback)
        servers += listener
        scope.launch {
            try {
                while (!listener.isClosed) {
                    val socket = listener.accept()
                    sockets.add(socket)
                    try {
                        socket.use {
                            it.soTimeout = 3_000
                            if (mode == PublishMode.TCP) echo(it) else respondHttp(it, role)
                        }
                    } catch (e: Exception) {
                        if (!listener.isClosed) record("Request closed: ${e.javaClass.simpleName}", counted = false)
                    } finally {
                        sockets.remove(socket)
                    }
                }
            } catch (e: SocketException) {
                if (!listener.isClosed) record("Listener error: ${e.javaClass.simpleName}", counted = false)
            } catch (e: Exception) {
                record("Listener error: ${e.javaClass.simpleName}", counted = false)
            }
        }
        return "127.0.0.1:${listener.localPort}"
    }

    private fun echo(socket: Socket) {
        val bytes = ByteArray(4096)
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        var total = 0
        while (total < 65536) {
            val size = input.read(bytes, 0, minOf(bytes.size, 65536 - total))
            if (size < 0) return
            output.write(bytes, 0, size)
            output.flush()
            total += size
            record("TCP echo · $size bytes")
        }
    }

    private fun respondHttp(socket: Socket, role: String?) {
        val input = socket.getInputStream()
        val header = ByteArrayOutputStream()
        var end = 0
        while (header.size() < 8192 && end != 0x0d0a0d0a) {
            val byte = input.read()
            if (byte < 0) return
            header.write(byte)
            end = (end shl 8) or byte
        }
        if (end != 0x0d0a0d0a) return
        val lines = header.toString(Charsets.US_ASCII.name()).split("\r\n")
        val request = lines.first().split(' ')
        if (request.size != 3) return
        val method = request[0].take(12)
        // Do not retain query parameters, Authorization headers or request bodies.
        val path = request[1].substringBefore('?').take(160)
        val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (length !in 0..16384 || lines.any { it.startsWith("Transfer-Encoding:", ignoreCase = true) }) {
            writeResponse(socket, "413 Payload Too Large", "text/plain", "Use Content-Length with a body up to 16 KiB.")
            return
        }
        val discard = ByteArray(1024)
        var remaining = length
        while (remaining > 0) {
            val size = input.read(discard, 0, minOf(discard.size, remaining))
            if (size < 0) return
            remaining -= size
        }
        // Only known paths are retained; arbitrary user path segments can contain secrets.
        record("HTTP $method ${when { path.startsWith("/api") -> "/api"; path == "/webhook" -> "/webhook"; path == "/" -> "/"; else -> "(other path)" }} · $length body bytes discarded")
        when {
            role == "api" || (role == null && path.startsWith("/api")) ->
                writeResponse(socket, "200 OK", "application/json", "{\"demo\":\"portal-android\",\"service\":\"api\",\"requests\":${activity.value.count}}")
            role == "webhook" || (method == "POST" && path == "/webhook") ->
                writeResponse(socket, "202 Accepted", "application/json", "{\"service\":\"webhook\",\"received\":true,\"bodyStored\":false}")
            else -> writeResponse(socket, "200 OK", "text/html; charset=utf-8", "<!doctype html><title>Portal HTTP demo</title><h1>Portal Android HTTP demo</h1><p>Try <a href='/api'>GET /api</a> or POST /webhook. No bodies, headers or query values are retained.</p>")
        }
    }

    private fun writeResponse(socket: Socket, status: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun record(line: String, counted: Boolean = true) {
        activity.update { previous ->
            DemoActivity(previous.count + if (counted) 1 else 0, (previous.lines + line).takeLast(40))
        }
    }

    fun stop() {
        servers.forEach { runCatching { it.close() } }
        runCatching { datagrams?.close() }
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } }; sockets.clear() }
        scope.cancel()
    }
}

data class DemoActivity(val count: Int = 0, val lines: List<String> = emptyList())
