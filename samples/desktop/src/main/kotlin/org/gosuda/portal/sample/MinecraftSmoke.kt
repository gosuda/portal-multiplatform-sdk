package org.gosuda.portal.sample

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.readPacket
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.readString
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.readVarInt
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.writePacket
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.writeString
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.writeUuid
import org.gosuda.portal.sample.content.minecraft.MinecraftProtocol.writeVarInt
import org.gosuda.portal.sample.content.minecraft.MinecraftServer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.UUID

/**
 * Headless Minecraft join smoke: starts the real [MinecraftServer] on
 * loopback and drives a fake 1.21.1 client through the full join path —
 * handshake → status ping → login → configuration → play — asserting the
 * server reaches each state and emits the expected packets.
 *
 * Proves playability at the protocol level without a real client.
 *
 * Run: `./gradlew :samples:desktop:minecraftSmoke`
 */
fun main() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
        MinecraftServer.start(scope)
        delay(300) // let the accept loop bind

        // ---- 1. status ping ---------------------------------------------------
        Socket("127.0.0.1", MinecraftServer.PORT).use { sock ->
            val input = DataInputStream(sock.getInputStream())
            val output = DataOutputStream(sock.getOutputStream())
            handshake(output, nextState = 1)
            writePacket(output, 0x00) {}                      // status request
            val (id, body) = readPacket(input) ?: error("no status response")
            check(id == 0x00) { "expected server_info 0x00, got 0x${id.toString(16)}" }
            val json = readString(DataInputStream(body.inputStream()))
            check(json.contains("\"protocol\":${MinecraftProtocol.PROTOCOL_VERSION}")) {
                "status json missing protocol: $json"
            }
            writePacket(output, 0x01) { it.writeLong(12345L) } // ping
            val (pid, pbody) = readPacket(input) ?: error("no pong")
            check(pid == 0x01) { "expected ping 0x01, got 0x${pid.toString(16)}" }
            check(DataInputStream(pbody.inputStream()).readLong() == 12345L) { "pong echo mismatch" }
            println("status ping OK — MOTD + pong")
        }

        // ---- 2. full join ------------------------------------------------------
        Socket("127.0.0.1", MinecraftServer.PORT).use { sock ->
            sock.soTimeout = 15_000
            val input = DataInputStream(sock.getInputStream())
            val output = DataOutputStream(sock.getOutputStream())
            handshake(output, nextState = 2)
            // login_start
            writePacket(output, 0x00) { out ->
                writeString(out, "SmokeBot")
                writeUuid(out, UUID.nameUUIDFromBytes("OfflinePlayer:SmokeBot".toByteArray()))
            }
            // login_success
            val (lid, lbody) = readPacket(input) ?: error("no login_success")
            check(lid == 0x02) { "expected login_success 0x02, got 0x${lid.toString(16)}" }
            val lp = DataInputStream(lbody.inputStream())
            MinecraftProtocol.readUuid(lp)
            check(readString(lp) == "SmokeBot") { "username echo mismatch" }
            // login_acknowledged → configuration
            writePacket(output, 0x03) {}
            println("login OK — entering configuration")

            // configuration: expect registry_data ×N + feature_flags + finish
            var registries = 0
            var sawFeatureFlags = false
            var finished = false
            val deadline = System.currentTimeMillis() + 15_000
            while (!finished && System.currentTimeMillis() < deadline) {
                val (id, body) = readPacket(input) ?: error("config stream ended")
                when (id) {
                    0x07 -> registries++
                    0x0c -> sawFeatureFlags = true
                    0x03 -> {
                        finished = true
                        writePacket(output, 0x03) {}           // finish ack
                    }
                    0x05 -> { // ping → pong
                        val p = DataInputStream(body.inputStream())
                        val pid = p.readInt()
                        writePacket(output, 0x06) { it.writeInt(pid) }
                    }
                    else -> { /* ignore */ }
                }
            }
            check(finished) { "server never sent finish_configuration" }
            check(registries >= 4) { "expected ≥4 registries, got $registries" }
            check(sawFeatureFlags) { "no feature_flags" }
            println("configuration OK — $registries registries synced")

            // play: expect login(0x2b), then a stream containing map_chunk(0x27)
            // and position(0x40). Answer keep_alives so the server keeps us.
            var sawLogin = false
            var chunks = 0
            var sawPosition = false
            var sawPlayerInfo = false
            val playDeadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < playDeadline && !(sawLogin && chunks >= 81 && sawPosition)) {
                val (id, body) = readPacket(input) ?: error("play stream ended")
                val p = DataInputStream(body.inputStream())
                when (id) {
                    0x2b -> {
                        sawLogin = true
                        val eid = p.readInt()
                        check(eid > 0) { "bad entity id" }
                    }
                    0x27 -> chunks++
                    0x40 -> sawPosition = true
                    0x3e -> sawPlayerInfo = true
                    0x26 -> { // keep_alive → echo
                        val kid = p.readLong()
                        writePacket(output, 0x18) { it.writeLong(kid) }
                    }
                    0x35 -> { // ping → pong
                        val pid = p.readLong()
                        writePacket(output, 0x27) { it.writeLong(pid) }
                    }
                    else -> { /* ignore */ }
                }
            }
            check(sawLogin) { "no play login packet" }
            check(chunks >= 81) { "expected 81 chunks (9×9), got $chunks" }
            check(sawPosition) { "no position/teleport packet" }
            println("play OK — login + $chunks chunks + spawn teleport" +
                (if (sawPlayerInfo) " + player_info" else ""))
        }

        println("MINECRAFT SMOKE OK")
    } finally {
        MinecraftServer.stop()
        scope.cancel()
    }
}

private fun handshake(output: DataOutputStream, nextState: Int) {
    writePacket(output, 0x00) { out ->
        writeVarInt(out, MinecraftProtocol.PROTOCOL_VERSION)
        writeString(out, "127.0.0.1")
        out.writeShort(MinecraftServer.PORT)
        writeVarInt(out, nextState)
    }
}
