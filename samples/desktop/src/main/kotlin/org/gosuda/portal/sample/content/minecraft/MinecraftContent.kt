package org.gosuda.portal.sample.content.minecraft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.sample.content.PublishableContent
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * Mock Minecraft server answering the server-list ping protocol, exposed
 * through `tcp=true` + `target_addr`. The relay hands out a public TCP
 * address (`relay.tcpAddr`); a real Minecraft client can add it and see the
 * MOTD, version, and player count this device reports.
 *
 * The same port also answers plain HTTP GETs with a status page, so the
 * public HTTPS URL shows something readable in a browser.
 *
 * Protocol implemented (wiki.vg Server List Ping):
 *   handshake (0x00, next-state=1) → status request (0x00) → status JSON
 *   ping (0x01) → pong echo
 */
object MinecraftContent : PublishableContent {
    override val id = "minecraft"
    override val title = "Minecraft server"
    override val summary = "Server-list ping a real MC client can see"
    override val detail = "tcp → 127.0.0.1:$PORT (also answers HTTP)"

    const val PORT = 25565
    private const val MC_VERSION = "Portal 1.21"
    private const val MC_PROTOCOL = 767
    private const val MOTD = "§bPortal §f— this \"server\" is a desktop"

    private var server: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val pings = AtomicLong(0)
    @Volatile private var startedAt = 0L

    override suspend fun start() {
        if (server != null) return
        val socket = ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))
        server = socket
        startedAt = System.currentTimeMillis()
        pings.set(0)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                launch { runCatching { handle(client) } }
            }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
        runCatching { server?.close() }
        server = null
    }

    override fun applyTo(config: PortalConfig): PortalConfig =
        config.copy(tcp = true, targetAddr = "127.0.0.1:$PORT")

    // ---- connection handling -------------------------------------------------

    private fun handle(client: Socket) {
        client.soTimeout = 15_000
        client.use { socket ->
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            // Peek at the first byte: 'G' (0x47) = HTTP GET; anything else is
            // treated as a Minecraft packet stream.
            input.mark(1)
            val first = input.read()
            if (first == 'G'.code) {
                input.reset()
                serveHttp(input, output)
            } else {
                input.reset()
                serveMinecraft(input, output)
            }
        }
    }

    // ---- Minecraft protocol --------------------------------------------------

    private fun serveMinecraft(input: DataInputStream, output: DataOutputStream) {
        // handshake packet: len, id=0x00, protocol, addr, port, next-state
        val handshake = readPacket(input) ?: return
        val hs = DataInputStream(handshake.inputStream())
        readVarInt(hs) // packet id
        readVarInt(hs) // protocol version
        readString(hs) // server address
        hs.readShort() // server port
        val nextState = readVarInt(hs)
        if (nextState != 1) return // only status ping is implemented

        readPacket(input) ?: return // status request (id 0x00)
        pings.incrementAndGet()
        writePacket(output, 0x00, statusJson().toByteArray(Charsets.UTF_8).let {
            val body = ByteArrayOutputStream()
            writeVarInt(DataOutputStream(body), it.size)
            body.write(it)
            body.toByteArray()
        })

        // Optional ping → pong echo.
        val ping = try {
            readPacket(input)
        } catch (_: Exception) {
            null
        } ?: return
        val pingData = DataInputStream(ping.inputStream())
        if (readVarInt(pingData) == 0x01 && pingData.available() >= 8) {
            val payload = pingData.readLong()
            val body = ByteArrayOutputStream()
            val out = DataOutputStream(body)
            writeVarInt(out, 0x01)
            out.writeLong(payload)
            writeRawPacket(output, body.toByteArray())
        }
    }

    private fun statusJson(): String {
        val uptime = (System.currentTimeMillis() - startedAt) / 1000
        return """{"version":{"name":"$MC_VERSION","protocol":$MC_PROTOCOL},"players":{"max":20,"online":1,"sample":[{"name":"portal-device","id":"00000000-0000-0000-0000-000000000000"}]},"description":{"text":"$MOTD"},"enforcesSecureChat":false,"_portal":{"pings":${pings.get()},"uptime_seconds":$uptime}}"""
    }

    /** Reads one length-prefixed packet; returns its payload. */
    private fun readPacket(input: DataInputStream): ByteArray? {
        val length = try {
            readVarInt(input)
        } catch (_: Exception) {
            return null
        }
        if (length <= 0 || length > 1_048_576) return null
        val buf = ByteArray(length)
        input.readFully(buf)
        return buf
    }

    /** Writes a packet whose payload already contains its id. */
    private fun writeRawPacket(output: DataOutputStream, payload: ByteArray) {
        writeVarInt(output, payload.size)
        output.write(payload)
        output.flush()
    }

    /** Writes a packet: id + string body. */
    private fun writePacket(output: DataOutputStream, id: Int, body: ByteArray) {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        writeVarInt(out, id)
        out.write(body)
        writeRawPacket(output, payload.toByteArray())
    }

    private fun readVarInt(input: DataInputStream): Int {
        var value = 0
        var shift = 0
        while (true) {
            val b = input.readByte().toInt() and 0xFF
            value = value or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return value
            shift += 7
            if (shift > 35) throw IllegalStateException("varint too long")
        }
    }

    private fun writeVarInt(output: DataOutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                output.writeByte(v)
                return
            }
            output.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    private fun readString(input: DataInputStream): String {
        val len = readVarInt(input)
        val buf = ByteArray(len)
        input.readFully(buf)
        return String(buf, Charsets.UTF_8)
    }

    // ---- HTTP fallback --------------------------------------------------------

    private fun serveHttp(input: DataInputStream, output: DataOutputStream) {
        input.readLine() // request line
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
        }
        val body = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Portal Minecraft sample</title><style>body{background:#080f1d;color:#f0f5fc;font-family:system-ui,sans-serif;max-width:640px;margin:60px auto;padding:0 24px;line-height:1.6}code{background:rgba(100,220,236,.12);color:#64dcec;padding:2px 8px;border-radius:6px}.card{background:#111e30;border-radius:14px;padding:20px;margin:14px 0}</style></head><body><h1>Minecraft ping from a desktop</h1><p>This device answers the Minecraft server-list protocol on <code>127.0.0.1:$PORT</code>, exposed through the relay's TCP address.</p><div class="card"><b>To see it in Minecraft:</b><br>1. Copy the <code>tcp_addr</code> from the Activity → relays panel.<br>2. Add it as a server in Minecraft multiplayer.<br>3. The MOTD and player count come from this device.</div><p style="color:#a7b8ce;font-size:14px">Pings served: ${pings.get()} · uptime ${(System.currentTimeMillis() - startedAt) / 1000}s</p></body></html>"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }
}
