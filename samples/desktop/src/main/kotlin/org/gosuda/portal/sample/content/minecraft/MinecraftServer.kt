package org.gosuda.portal.sample.content.minecraft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.NbtCompound
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.PROTOCOL_VERSION
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.emptyNbt
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.nbt
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.readPacket
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.readPosition
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.readString
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.readVarInt
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.textComponent
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.writePacket
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.writePosition
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.writeString
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.writeUuid
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.writeVarInt
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.writeVarLong
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A genuinely playable Minecraft Java-edition server for 1.21 / 1.21.1
 * (protocol 767), exposed through `tcp=true` + `target_addr`.
 *
 * Implements the real join path — handshake → status ping → login →
 * configuration (registry sync) → play — plus the live loop: keep-alives,
 * movement broadcast, creative block edits, chat, and player entities.
 * The world is a flat stone platform over void (creative, peaceful); it is
 * a demo stage, not a terrain generator.
 *
 * The same port answers the server-list ping and plain HTTP GETs, so the
 * public HTTPS URL also renders a status page in a browser.
 *
 * Offline mode: no encryption, no compression, no authentication — fine
 * for a demo endpoint behind a relay.
 */
object MinecraftServer {

    const val PORT = 25565
    private const val VIEW_DISTANCE = 4
    private const val CHUNK_RADIUS = 4          // 9×9 chunks around origin
    private const val SECTIONS = 24             // y ∈ [-64, 320)
    private const val MIN_Y = -64
    private const val FLOOR_Y = -48             // top surface of the stone floor
    private const val SPAWN_Y = FLOOR_Y.toDouble()
    private const val PLAYER_ENTITY_TYPE = 128  // minecraft:player (1.21.1)
    private const val KEEP_ALIVE_MS = 10_000L
    private const val TIME_UPDATE_MS = 20_000L
    private const val MOTD = "§bPortal §f— a real MC server on a desktop"

    // Block state ids (1.21.1 global palette).
    private const val BLOCK_AIR = 0
    private const val BLOCK_STONE = 1
    private const val BLOCK_GRASS = 9
    private const val BLOCK_BEDROCK = 79

    private val PLAINS_ID = MinecraftRegistries.REGISTRIES
        .getValue("minecraft:worldgen/biome").indexOf("minecraft:plains")

    private var server: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val sessions = CopyOnWriteArrayList<Session>()
    private val edits = ConcurrentHashMap<Long, Int>()   // packed pos → block state
    private val nextEntityId = AtomicInteger(100)
    private val pings = AtomicLong(0)
    private val joins = AtomicLong(0)
    @Volatile private var startedAt = 0L

    // ---- lifecycle -------------------------------------------------------------

    fun start(scope: CoroutineScope) {
        if (server != null) return
        val socket = ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))
        server = socket
        this.scope = scope
        startedAt = System.currentTimeMillis()
        pings.set(0); joins.set(0)
        scope.launch {
            while (isActive) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                launch { runCatching { handle(client) } }
            }
        }
        // Daylight clock: keeps the world bright and exercises a periodic
        // broadcast path.
        scope.launch {
            while (isActive) {
                delay(TIME_UPDATE_MS)
                broadcast { it.sendUpdateTime() }
            }
        }
    }

    fun stop() {
        scope?.cancel(); scope = null
        sessions.forEach { it.close() }
        sessions.clear()
        edits.clear()
        runCatching { server?.close() }
        server = null
    }

    val isRunning: Boolean get() = server != null
    val playerCount: Int get() = sessions.count { it.state == SessionState.PLAY }

    // ---- connection entry --------------------------------------------------------

    private fun handle(client: Socket) {
        client.soTimeout = 60_000
        client.tcpNoDelay = true
        val input = DataInputStream(java.io.BufferedInputStream(client.getInputStream(), 8192))
        val output = DataOutputStream(client.getOutputStream())
        val session = Session(client, output)
        try {
            // Peek: HTTP GETs start with 'G' (0x47) — never a valid VarInt
            // packet length on this port, so sniff the first byte.
            input.mark(1)
            val first = input.read()
            input.reset()
            if (first == 'G'.code) { serveHttp(input, output); return }

            val (hsId, hsBody) = readPacket(input) ?: return
            if (hsId != 0x00) return
            val hs = DataInputStream(hsBody.inputStream())
            val protocol = readVarInt(hs)
            readString(hs)          // server address
            hs.readShort()          // server port
            val nextState = readVarInt(hs)
            when (nextState) {
                1 -> serveStatus(input, output)
                2 -> {
                    sessions += session
                    try { session.login(input, protocol) } finally {
                        sessions -= session
                        session.onLeft()
                    }
                }
            }
        } catch (_: EOFException) {
        } catch (_: Exception) {
        } finally {
            session.close()
        }
    }

    // ---- status (server-list ping) ------------------------------------------------

    private fun serveStatus(input: DataInputStream, output: DataOutputStream) {
        pings.incrementAndGet()
        var responded = false
        while (true) {
            val (id, body) = readPacket(input) ?: return
            when (id) {
                0x00 -> if (!responded) {
                    responded = true
                    writePacket(output, 0x00) { writeString(it, statusJson()) }
                }
                0x01 -> {
                    val p = DataInputStream(body.inputStream())
                    val time = p.readLong()
                    writePacket(output, 0x01) { it.writeLong(time) }
                    return
                }
                else -> return
            }
        }
    }

    private fun statusJson(): String {
        val uptime = (System.currentTimeMillis() - startedAt) / 1000
        return """{"version":{"name":"Portal ${MinecraftProtocol.GAME_VERSION}","protocol":$PROTOCOL_VERSION},""" +
            """"players":{"max":20,"online":$playerCount},""" +
            """"description":{"text":"$MOTD"},""" +
            """"portal":{"uptime_seconds":$uptime,"pings":${pings.get()},"joins":${joins.get()},"edits":${edits.size}}}"""
    }

    // ---- HTTP fallback -------------------------------------------------------------

    private fun serveHttp(input: DataInputStream, output: DataOutputStream) {
        val reader = input.bufferedReader()
        reader.readLine() ?: return
        while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
        val body = """<!doctype html><meta charset="utf-8"><title>Portal Minecraft</title>
<body style="background:#080f1d;color:#f0f5fc;font-family:system-ui;max-width:560px;margin:60px auto;line-height:1.6">
<h1>Minecraft server</h1>
<p>A real Minecraft ${MinecraftProtocol.GAME_VERSION} server running inside this desktop app, exposed through a Portal relay.</p>
<p><b>Add the TCP address</b> (the relay's <code>tcpAddr</code>, shown in the app) as a server in Minecraft ${MinecraftProtocol.GAME_VERSION} — you'll spawn on a flat creative platform.</p>
<p style="color:#a7b8ce">Players online: $playerCount · pings: ${pings.get()} · joins: ${joins.get()} · block edits: ${edits.size}</p>"""
        val bytes = body.toByteArray()
        output.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    // ---- broadcast ------------------------------------------------------------------

    private inline fun broadcast(crossinline send: (Session) -> Unit) {
        for (s in sessions) if (s.state == SessionState.PLAY) send(s)
    }

    private fun broadcastChat(text: String) {
        val component = textComponent(text)
        broadcast { s -> s.send(0x6c) { out -> out.write(component); out.writeBoolean(false) } }
    }

    // ---- session ----------------------------------------------------------------------

    private enum class SessionState { LOGIN, CONFIG, PLAY }

    private class Session(
        private val socket: Socket,
        private val output: DataOutputStream,
    ) {
        @Volatile var state = SessionState.LOGIN
        @Volatile var closed = false
        private val writeLock = Any()

        lateinit var name: String
        lateinit var uuid: UUID
        var entityId = 0
        @Volatile var x = 8.5; @Volatile var y = SPAWN_Y; @Volatile var z = 8.5
        @Volatile var yaw = 0f; @Volatile var pitch = 0f
        @Volatile var onGround = true
        @Volatile private var lastKeepAlive = 0L
        @Volatile private var awaitingKeepAlive = false

        fun close() {
            closed = true
            runCatching { socket.close() }
        }

        /** Thread-safe framed write. */
        fun send(id: Int, body: (DataOutputStream) -> Unit) {
            if (closed) return
            synchronized(writeLock) {
                if (closed) return
                runCatching { writePacket(output, id, body) }
            }
        }

        // ---- login state -----------------------------------------------------------

        fun login(input: DataInputStream, protocol: Int) {
            val (id, body) = readPacket(input) ?: return
            if (id != 0x00) return                       // login_start
            val p = DataInputStream(body.inputStream())
            name = readString(p).ifBlank { "player" }.take(16)
            uuid = try { MinecraftProtocol.readUuid(p) }
                catch (_: Exception) { UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray()) }
            entityId = nextEntityId.getAndIncrement()

            if (protocol != PROTOCOL_VERSION) {
                send(0x00) { out -> out.write(textComponent(
                    "Version mismatch — this demo speaks ${MinecraftProtocol.GAME_VERSION} (protocol $PROTOCOL_VERSION)")) }
                return
            }
            // login_success (0x02): uuid, username, properties[]
            send(0x02) { out ->
                writeUuid(out, uuid)
                writeString(out, name)
                writeVarInt(out, 0)
            }
            // login_acknowledged (0x03) → configuration
            val (ackId, _) = readPacket(input) ?: return
            if (ackId != 0x03) return
            state = SessionState.CONFIG
            configure(input)
        }

        // ---- configuration state ------------------------------------------------------

        private fun configure(input: DataInputStream) {
            // registry_data × N — keys only; the client fills vanilla values.
            for ((registry, keys) in MinecraftRegistries.REGISTRIES) {
                send(0x07) { out ->
                    writeString(out, registry)
                    writeVarInt(out, keys.size)
                    for (key in keys) {
                        writeString(out, key)
                        out.writeBoolean(false)         // no NBT → client default
                    }
                }
            }
            // feature_flags: vanilla only
            send(0x0c) { out ->
                writeVarInt(out, 1)
                writeString(out, "minecraft:vanilla")
            }
            // finish_configuration (0x03)
            send(0x03) {}

            var finished = false
            var deadline = System.currentTimeMillis() + 15_000
            while (!finished && !closed && System.currentTimeMillis() < deadline) {
                val (id, body) = readPacket(input) ?: return
                val p = DataInputStream(body.inputStream())
                when (id) {
                    0x03 -> { finished = true }                       // finish_configuration ack
                    0x05 -> {                                         // ping → pong
                        val pid = p.readInt()
                        send(0x06) { out -> out.writeInt(pid) }
                    }
                    0x04 -> { /* keep_alive — ignore during config */ }
                    else -> { /* settings, known packs, plugin messages — ignore */ }
                }
            }
            if (!finished) return
            state = SessionState.PLAY
            joins.incrementAndGet()
            enterPlay(input)
        }

        // ---- play state ----------------------------------------------------------------

        private fun enterPlay(input: DataInputStream) {
            // login (0x2b)
            send(0x2b) { out ->
                out.writeInt(entityId)
                out.writeBoolean(false)                 // isHardcore
                writeVarInt(out, 1)                     // worldNames
                writeString(out, "minecraft:overworld")
                writeVarInt(out, 20)                    // maxPlayers
                writeVarInt(out, VIEW_DISTANCE)
                writeVarInt(out, VIEW_DISTANCE)         // simulationDistance
                out.writeBoolean(false)                 // reducedDebugInfo
                out.writeBoolean(true)                  // enableRespawnScreen
                out.writeBoolean(false)                 // doLimitedCrafting
                // SpawnInfo
                writeVarInt(out, 0)                     // dimensionType id: overworld
                writeString(out, "minecraft:overworld") // dimensionName
                out.writeLong(0L)                       // hashedSeed
                out.writeByte(1)                        // gamemode: creative
                out.writeByte(-1)                       // previousGamemode
                out.writeBoolean(false)                 // isDebug
                out.writeBoolean(true)                  // isFlat
                out.writeBoolean(false)                 // death location absent
                writeVarInt(out, 0)                     // portalCooldown
                out.writeBoolean(false)                 // enforcesSecureChat
            }
            // difficulty: peaceful
            send(0x0b) { out -> out.writeByte(0); out.writeBoolean(false) }
            // game_state_change: level_chunks_load_start, then creative mode
            send(0x22) { out -> out.writeByte(13); out.writeFloat(0f) }
            send(0x22) { out -> out.writeByte(3); out.writeFloat(1f) }
            // abilities: creative (invulnerable, flying allowed, creative)
            send(0x38) { out -> out.writeByte(0x0D); out.writeFloat(0.05f); out.writeFloat(0.1f) }
            // held item slot
            send(0x53) { out -> out.writeByte(0) }
            // update_view_position → center chunk
            send(0x54) { out -> writeVarInt(out, 0); writeVarInt(out, 0) }
            // chunk batch: 9×9 flat chunks
            send(0x0d) {}
            for (cx in -CHUNK_RADIUS..CHUNK_RADIUS) {
                for (cz in -CHUNK_RADIUS..CHUNK_RADIUS) {
                    sendChunk(cx = cx, cz = cz)
                }
            }
            send(0x0c) { out -> writeVarInt(out, (2 * CHUNK_RADIUS + 1) * (2 * CHUNK_RADIUS + 1)) }
            // spawn position + initial teleport
            send(0x56) { out -> writePosition(out, 8, FLOOR_Y, 8); out.writeFloat(0f) }
            sendPosition()
            sendUpdateTime()
            sendHealth()

            // Tab list + entities for everyone already in play, then announce us.
            for (other in sessions) {
                if (other === this || other.state != SessionState.PLAY) continue
                sendPlayerInfo(other, add = true)
                sendSpawnPlayer(other)
                other.sendPlayerInfo(this, add = true)
                other.sendSpawnPlayer(this)
            }
            broadcastChat("§e$name joined the game")

            // keep-alive loop on the session's own coroutine budget: piggyback
            // on the read loop — a watchdog coroutine per session.
            val watchdog = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                while (isActive && !closed && state == SessionState.PLAY) {
                    delay(KEEP_ALIVE_MS)
                    if (awaitingKeepAlive &&
                        System.currentTimeMillis() - lastKeepAlive > KEEP_ALIVE_MS * 3) {
                        close(); break
                    }
                    lastKeepAlive = System.currentTimeMillis()
                    awaitingKeepAlive = true
                    send(0x26) { out -> out.writeLong(lastKeepAlive) }
                }
            }

            try {
                playLoop(input)
            } finally {
                watchdog.cancel()
            }
        }

        private fun playLoop(input: DataInputStream) {
            while (!closed) {
                val (id, body) = readPacket(input) ?: return
                val p = DataInputStream(body.inputStream())
                when (id) {
                    0x00 -> { /* teleport_confirm */ readVarInt(p) }
                    0x18 -> { // keep_alive echo
                        val kid = p.readLong()
                        if (kid == lastKeepAlive) awaitingKeepAlive = false
                    }
                    0x21 -> { // ping_request → ping_response
                        val pid = p.readLong()
                        send(0x36) { out -> out.writeLong(pid) }
                    }
                    0x1a -> { // position
                        val nx = p.readDouble(); val ny = p.readDouble(); val nz = p.readDouble()
                        onGround = p.readBoolean()
                        moved(nx, ny, nz, null, null)
                    }
                    0x1b -> { // position_look
                        val nx = p.readDouble(); val ny = p.readDouble(); val nz = p.readDouble()
                        val nyaw = p.readFloat(); val npitch = p.readFloat()
                        onGround = p.readBoolean()
                        moved(nx, ny, nz, nyaw, npitch)
                    }
                    0x1c -> { // look
                        yaw = p.readFloat(); pitch = p.readFloat()
                        onGround = p.readBoolean()
                        broadcastLook()
                    }
                    0x1d -> { onGround = p.readBoolean() } // flying
                    0x06 -> { // chat_message
                        val msg = readString(p).take(256)
                        if (msg.isNotBlank()) broadcastChat("§f<$name>§r $msg")
                    }
                    0x04, 0x05 -> { // chat_command / signed
                        val cmd = readString(p)
                        if (cmd.isNotBlank()) broadcastChat("§7$name issued /$cmd")
                    }
                    0x36 -> { // arm_animation → broadcast swing
                        val hand = readVarInt(p)
                        broadcastExcept(this) { s ->
                            s.send(0x03) { out -> writeVarInt(out, entityId); out.writeByte(if (hand == 0) 0 else 3) }
                        }
                    }
                    0x24 -> handleDig(p)
                    0x38 -> handlePlace(p)
                    0x0c -> { /* configuration_acknowledged — mid-play reconfig; ignore */ }
                    else -> { /* unhandled play packet — ignore */ }
                }
            }
        }

        // ---- movement ------------------------------------------------------------------

        private fun moved(nx: Double, ny: Double, nz: Double, nyaw: Float?, npitch: Float?) {
            val dx = ((nx * 32 - x * 32) * 128).toInt()
            val dy = ((ny * 32 - y * 32) * 128).toInt()
            val dz = ((nz * 32 - z * 32) * 128).toInt()
            val teleport = dx !in -32768..32767 || dy !in -32768..32767 || dz !in -32768..32767
            x = nx; y = ny; z = nz
            if (nyaw != null) { yaw = nyaw; pitch = npitch ?: pitch }
            val yawB = angleByte(yaw); val pitchB = angleByte(pitch)
            broadcastExcept(this) { s ->
                when {
                    teleport -> s.send(0x70) { out ->
                        writeVarInt(out, entityId)
                        out.writeDouble(x); out.writeDouble(y); out.writeDouble(z)
                        out.writeByte(yawB.toInt()); out.writeByte(pitchB.toInt())
                        out.writeBoolean(onGround)
                    }
                    nyaw != null -> s.send(0x2f) { out ->
                        writeVarInt(out, entityId)
                        out.writeShort(dx); out.writeShort(dy); out.writeShort(dz)
                        out.writeByte(yawB.toInt()); out.writeByte(pitchB.toInt())
                        out.writeBoolean(onGround)
                    }
                    else -> s.send(0x2e) { out ->
                        writeVarInt(out, entityId)
                        out.writeShort(dx); out.writeShort(dy); out.writeShort(dz)
                        out.writeBoolean(onGround)
                    }
                }
                if (nyaw != null) s.send(0x48) { out ->
                    writeVarInt(out, entityId); out.writeByte(yawB.toInt())
                }
            }
        }

        private fun broadcastLook() {
            val yawB = angleByte(yaw); val pitchB = angleByte(pitch)
            broadcastExcept(this) { s ->
                s.send(0x30) { out ->
                    writeVarInt(out, entityId)
                    out.writeByte(yawB.toInt()); out.writeByte(pitchB.toInt())
                    out.writeBoolean(onGround)
                }
                s.send(0x48) { out -> writeVarInt(out, entityId); out.writeByte(yawB.toInt()) }
            }
        }

        private fun angleByte(deg: Float): Byte =
            (deg * 256f / 360f).toInt().toByte()

        // ---- block edits -----------------------------------------------------------------

        private fun handleDig(p: DataInputStream) {
            val status = readVarInt(p)
            val (bx, by, bz) = readPosition(p)
            p.readByte()                                // face
            val seq = readVarInt(p)
            // Always ack the sequence — the client waits on it.
            send(0x05) { out -> writeVarInt(out, seq) }
            val key = packPos(bx, by, bz)
            when (status) {
                0, 2 -> { // started / finished digging → creative insta-break
                    if (blockAt(bx, by, bz) != BLOCK_AIR) {
                        edits[key] = BLOCK_AIR
                        broadcastBlock(bx, by, bz, BLOCK_AIR)
                    }
                }
                1 -> { // aborted → restore what the client thinks is there
                    send(0x09) { out ->
                        writePosition(out, bx, by, bz)
                        writeVarInt(out, blockAt(bx, by, bz))
                    }
                }
            }
        }

        private fun handlePlace(p: DataInputStream) {
            readVarInt(p)                               // hand
            val (bx, by, bz) = readPosition(p)
            val dir = readVarInt(p)
            p.readFloat(); p.readFloat(); p.readFloat() // cursor
            p.readBoolean()                             // insideBlock
            val seq = readVarInt(p)
            send(0x05) { out -> writeVarInt(out, seq) }
            // Adjacent position from the clicked face.
            val (tx, ty, tz) = when (dir) {
                0 -> Triple(bx, by - 1, bz); 1 -> Triple(bx, by + 1, bz)
                2 -> Triple(bx, by, bz - 1); 3 -> Triple(bx, by, bz + 1)
                4 -> Triple(bx - 1, by, bz); else -> Triple(bx + 1, by, bz)
            }
            if (ty in MIN_Y until MIN_Y + SECTIONS * 16 && blockAt(tx, ty, tz) == BLOCK_AIR) {
                edits[packPos(tx, ty, tz)] = BLOCK_GRASS
                broadcastBlock(tx, ty, tz, BLOCK_GRASS)
            }
        }

        private fun broadcastBlock(bx: Int, by: Int, bz: Int, state: Int) {
            broadcast { s ->
                s.send(0x09) { out -> writePosition(out, bx, by, bz); writeVarInt(out, state) }
            }
        }

        // ---- join/leave announcements -------------------------------------------------------

        fun onLeft() {
            if (state != SessionState.PLAY) return
            state = SessionState.LOGIN
            broadcastChat("§e$name left the game")
            broadcastExcept(this) { s ->
                s.send(0x3d) { out ->                    // player_remove
                    writeVarInt(out, 1); writeUuid(out, uuid)
                }
                s.send(0x42) { out ->                    // entity_destroy
                    writeVarInt(out, 1); writeVarInt(out, entityId)
                }
            }
        }

        // ---- packet helpers -----------------------------------------------------------------

        private fun sendPlayerInfo(other: Session, add: Boolean) {
            send(0x3e) { out ->
                out.writeByte(if (add) 0x01 or 0x04 or 0x08 or 0x10 else 0x04 or 0x08 or 0x10)
                writeVarInt(out, 1)
                writeUuid(out, other.uuid)
                if (add) {
                    writeString(out, other.name)
                    writeVarInt(out, 0)                 // properties
                }
                writeVarInt(out, 1)                     // gamemode: creative
                writeVarInt(out, 1)                     // listed
                writeVarInt(out, 0)                     // latency
            }
        }

        private fun sendSpawnPlayer(other: Session) {
            send(0x01) { out ->
                writeVarInt(out, other.entityId)
                writeUuid(out, other.uuid)
                writeVarInt(out, PLAYER_ENTITY_TYPE)
                out.writeDouble(other.x); out.writeDouble(other.y); out.writeDouble(other.z)
                out.writeByte(other.angleByte(other.pitch).toInt())
                out.writeByte(other.angleByte(other.yaw).toInt())
                out.writeByte(other.angleByte(other.yaw).toInt()) // headPitch
                writeVarInt(out, 0)                     // objectData
                out.writeShort(0); out.writeShort(0); out.writeShort(0) // velocity
            }
        }

        private fun sendPosition() {
            send(0x40) { out ->
                out.writeDouble(x); out.writeDouble(y); out.writeDouble(z)
                out.writeFloat(yaw); out.writeFloat(pitch)
                out.writeByte(0)                        // no relative flags
                writeVarInt(out, 1)                     // teleportId
            }
        }

        fun sendUpdateTime() {
            send(0x64) { out -> out.writeLong(0L); out.writeLong(6000L) } // noon
        }

        private fun sendHealth() {
            send(0x5d) { out -> out.writeFloat(20f); writeVarInt(out, 20); out.writeFloat(5f) }
        }

        private fun sendChunk(cx: Int, cz: Int) {
            send(0x27) { o ->
                o.writeInt(cx); o.writeInt(cz)
                o.write(heightmaps(cx, cz))
                val data = chunkData(cx, cz)
                writeVarInt(o, data.size); o.write(data)
                writeVarInt(o, 0)                       // blockEntities
                // Light: sky light everywhere, no block light.
                writeVarInt(o, 1); o.writeLong(SKY_MASK)      // skyLightMask
                writeVarInt(o, 0)                             // blockLightMask
                writeVarInt(o, 0)                             // emptySkyLightMask
                writeVarInt(o, 1); o.writeLong(SKY_MASK)      // emptyBlockLightMask
                writeVarInt(o, SECTIONS + 2)                  // skyLight arrays
                repeat(SECTIONS + 2) { writeVarInt(o, 2048); o.write(FULL_LIGHT) }
                writeVarInt(o, 0)                             // blockLight arrays
            }
        }
    }

    private inline fun broadcastExcept(except: Session, crossinline send: (Session) -> Unit) {
        for (s in sessions) if (s !== except && s.state == SessionState.PLAY) send(s)
    }

    // ---- world ----------------------------------------------------------------------

    private const val SKY_MASK = (1L shl (SECTIONS + 2)) - 1
    private val FULL_LIGHT = ByteArray(2048) { 0xFF.toByte() }

    private fun packPos(x: Int, y: Int, z: Int): Long =
        (x.toLong() and 0x3FFFFFF shl 38) or (z.toLong() and 0x3FFFFFF shl 12) or (y.toLong() and 0xFFF)

    private fun blockAt(x: Int, y: Int, z: Int): Int =
        edits[packPos(x, y, z)] ?: if (y < FLOOR_Y) BLOCK_STONE else BLOCK_AIR

    /** Chunk data buffer: 24 sections, paletted containers. */
    private fun chunkData(cx: Int, cz: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        for (section in 0 until SECTIONS) {
            val baseY = MIN_Y + section * 16
            // Collect the distinct states in this section.
            val palette = LinkedHashMap<Int, Int>()     // state → index
            val indices = ShortArray(4096)
            var nonAir = 0
            for (i in 0 until 4096) {
                val lx = i and 15
                val ly = (i shr 8) and 15
                val lz = (i shr 4) and 15
                val state = blockAt(cx * 16 + lx, baseY + ly, cz * 16 + lz)
                if (state != BLOCK_AIR) nonAir++
                val idx = palette.getOrPut(state) { palette.size }
                indices[i] = idx.toShort()
            }
            out.writeShort(nonAir)
            writeBlockStates(out, palette, indices)
            // Biomes: single-value palette (plains).
            out.writeByte(0)
            writeVarInt(out, PLAINS_ID)
            writeVarInt(out, 0)
        }
        return bos.toByteArray()
    }

    private fun writeBlockStates(out: DataOutputStream, palette: Map<Int, Int>, indices: ShortArray) {
        if (palette.size == 1) {
            out.writeByte(0)                            // bitsPerEntry = 0
            writeVarInt(out, palette.keys.first())      // single palette entry
            writeVarInt(out, 0)                         // no data
            return
        }
        val bpe = maxOf(4, ceilLog2(palette.size))
        out.writeByte(bpe)
        writeVarInt(out, palette.size)
        for (state in palette.keys) writeVarInt(out, state)
        val perLong = 64 / bpe
        val longs = (4096 + perLong - 1) / perLong
        writeVarInt(out, longs)
        val mask = (1L shl bpe) - 1
        var i = 0
        repeat(longs) {
            var l = 0L
            for (j in 0 until perLong) {
                if (i < 4096) {
                    l = l or ((indices[i].toLong() and mask) shl (j * bpe))
                    i++
                }
            }
            out.writeLong(l)
        }
    }

    /** MOTION_BLOCKING heightmap: highest non-air y+1 per column, 9 bits each. */
    private fun heightmaps(cx: Int, cz: Int): ByteArray {
        val heights = IntArray(256)
        for (lx in 0 until 16) for (lz in 0 until 16) {
            var h = MIN_Y
            for (y in MIN_Y + SECTIONS * 16 - 1 downTo MIN_Y) {
                if (blockAt(cx * 16 + lx, y, cz * 16 + lz) != BLOCK_AIR) { h = y + 1; break }
            }
            heights[lz * 16 + lx] = h - MIN_Y           // stored relative to minY
        }
        val bpe = 9
        val perLong = 64 / bpe
        val longs = (256 + perLong - 1) / perLong
        val data = LongArray(longs)
        var i = 0
        for (l in 0 until longs) {
            var v = 0L
            for (j in 0 until perLong) {
                if (i < 256) { v = v or ((heights[i].toLong() and 0x1FF) shl (j * bpe)); i++ }
            }
            data[l] = v
        }
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        // LongArray tag (12) — written manually; NbtCompound has no long-array.
        out.writeByte(10); writeString(out, "")         // root compound
        out.writeByte(12); writeString(out, "MOTION_BLOCKING")
        out.writeInt(data.size)
        for (l in data) out.writeLong(l)
        out.writeByte(0)
        return bos.toByteArray()
    }

    private fun ceilLog2(n: Int): Int {
        var bits = 0; var v = n - 1
        while (v > 0) { bits++; v = v shr 1 }
        return bits
    }
}
