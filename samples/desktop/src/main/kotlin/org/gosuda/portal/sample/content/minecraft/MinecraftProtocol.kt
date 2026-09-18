package org.gosuda.portal.sample.content.minecraft

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Minimal Minecraft Java-edition protocol codec for 1.21 / 1.21.1
 * (protocol 767). Covers the wire primitives the playable server needs:
 * VarInt framing, strings, positions, UUIDs, and a small NBT writer for
 * text components and (empty) heightmaps.
 *
 * No compression and no encryption — the server runs in offline mode, so
 * `login_start` is answered directly with `login_success`.
 */
object MinecraftProtocol {

    const val PROTOCOL_VERSION = 767
    const val GAME_VERSION = "1.21.1"

    // ---- VarInt / VarLong ----------------------------------------------------

    fun writeVarInt(out: DataOutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) { out.writeByte(v); return }
            out.writeByte((v and 0x7F) or 0x80); v = v ushr 7
        }
    }

    fun writeVarLong(out: DataOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) { out.writeByte(v.toInt()); return }
            out.writeByte(((v and 0x7F) or 0x80).toInt()); v = v ushr 7
        }
    }

    fun readVarInt(input: DataInputStream): Int {
        var value = 0; var shift = 0
        while (true) {
            val b = input.readByte().toInt() and 0xFF
            value = value or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return value
            shift += 7
            if (shift > 35) throw IllegalStateException("varint too long")
        }
    }

    // ---- scalars ---------------------------------------------------------------

    fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarInt(out, bytes.size)
        out.write(bytes)
    }

    fun readString(input: DataInputStream): String {
        val len = readVarInt(input)
        require(len in 0..32767 * 4) { "string too long: $len" }
        val buf = ByteArray(len)
        input.readFully(buf)
        return String(buf, Charsets.UTF_8)
    }

    fun writeUuid(out: DataOutputStream, uuid: UUID) {
        out.writeLong(uuid.mostSignificantBits)
        out.writeLong(uuid.leastSignificantBits)
    }

    fun readUuid(input: DataInputStream): UUID =
        UUID(input.readLong(), input.readLong())

    /** Block position packed as x:26 | z:26 | y:12 (two's complement). */
    fun writePosition(out: DataOutputStream, x: Int, y: Int, z: Int) {
        val packed = ((x.toLong() and 0x3FFFFFF) shl 38) or
            ((z.toLong() and 0x3FFFFFF) shl 12) or
            (y.toLong() and 0xFFF)
        out.writeLong(packed)
    }

    fun readPosition(input: DataInputStream): Triple<Int, Int, Int> {
        val v = input.readLong()
        var x = (v shr 38).toInt()
        var z = ((v shl 26) shr 38).toInt()
        var y = ((v shl 52) shr 52).toInt()
        return Triple(x, y, z)
    }

    // ---- packet framing --------------------------------------------------------

    /** Reads one length-prefixed packet; returns (id, payload) or null on EOF. */
    fun readPacket(input: DataInputStream): Pair<Int, ByteArray>? {
        val length = try {
            readVarInt(input)
        } catch (_: EOFException) {
            return null
        } catch (_: Exception) {
            return null
        }
        if (length <= 0 || length > 2_097_152) return null
        val buf = ByteArray(length)
        input.readFully(buf)
        val payload = DataInputStream(buf.inputStream())
        val id = readVarInt(payload)
        val rest = ByteArray(payload.available())
        payload.readFully(rest)
        return id to rest
    }

    /** Writes a framed packet: VarInt length + VarInt id + body. */
    fun writePacket(output: DataOutputStream, id: Int, writeBody: (DataOutputStream) -> Unit) {
        val body = ByteArrayOutputStream()
        val bodyOut = DataOutputStream(body)
        writeVarInt(bodyOut, id)
        writeBody(bodyOut)
        val bytes = body.toByteArray()
        writeVarInt(output, bytes.size)
        output.write(bytes)
        output.flush()
    }

    // ---- NBT (minimal writer) ---------------------------------------------------
    // Tag ids: End=0 Byte=1 Short=2 Int=3 Long=4 Float=5 Double=6
    //          ByteArray=7 String=8 List=9 Compound=10 IntArray=11 LongArray=12

    private const val TAG_END: Byte = 0
    private const val TAG_BYTE: Byte = 1
    private const val TAG_INT: Byte = 3
    private const val TAG_LONG: Byte = 4
    private const val TAG_STRING: Byte = 8
    private const val TAG_LIST: Byte = 9
    private const val TAG_COMPOUND: Byte = 10

    /** A writable NBT compound. */
    class NbtCompound {
        private val entries = mutableListOf<Triple<String, Byte, Any>>()

        fun putString(name: String, value: String) = apply { entries += Triple(name, TAG_STRING, value) }
        fun putInt(name: String, value: Int) = apply { entries += Triple(name, TAG_INT, value) }
        fun putLong(name: String, value: Long) = apply { entries += Triple(name, TAG_LONG, value) }
        fun putByte(name: String, value: Byte) = apply { entries += Triple(name, TAG_BYTE, value) }
        fun putBoolean(name: String, value: Boolean) = apply { entries += Triple(name, TAG_BYTE, if (value) 1.toByte() else 0.toByte()) }
        fun putCompound(name: String, value: NbtCompound) = apply { entries += Triple(name, TAG_COMPOUND, value) }
        fun putList(name: String, elementTag: Byte, values: List<NbtCompound>) =
            apply { entries += Triple(name, TAG_LIST, NbtList(elementTag, values)) }

        fun writeTo(out: DataOutputStream, named: Boolean = true) {
            if (named) {
                out.writeByte(TAG_COMPOUND.toInt())
                writeString(out, "") // root name is empty
            }
            writeCompoundPayload(out)
        }

        internal fun writeCompoundPayload(out: DataOutputStream) {
            for ((name, tag, value) in entries) {
                out.writeByte(tag.toInt())
                writeString(out, name)
                writePayload(out, tag, value)
            }
            out.writeByte(TAG_END.toInt())
        }

        private fun writePayload(out: DataOutputStream, tag: Byte, value: Any) {
            when (tag) {
                TAG_BYTE -> out.writeByte((value as Byte).toInt())
                TAG_INT -> out.writeInt(value as Int)
                TAG_LONG -> out.writeLong(value as Long)
                TAG_STRING -> writeString(out, value as String)
                TAG_COMPOUND -> (value as NbtCompound).writeCompoundPayload(out)
                TAG_LIST -> {
                    val list = value as NbtList
                    out.writeByte(list.elementTag.toInt())
                    out.writeInt(list.values.size)
                    for (item in list.values) item.writeCompoundPayload(out)
                }
                else -> throw IllegalArgumentException("unsupported tag $tag")
            }
        }
    }

    private class NbtList(val elementTag: Byte, val values: List<NbtCompound>)

    /** Serializes a root compound to bytes (named root, empty name). */
    fun nbt(compound: NbtCompound): ByteArray {
        val bos = ByteArrayOutputStream()
        compound.writeTo(DataOutputStream(bos), named = true)
        return bos.toByteArray()
    }

    /** A plain-text chat component as NBT: {"text":"..."}. */
    fun textComponent(text: String): ByteArray =
        nbt(NbtCompound().putString("text", text))

    /** An empty compound (used for heightmaps). */
    fun emptyNbt(): ByteArray = nbt(NbtCompound())

    // ---- helpers ----------------------------------------------------------------

    fun buffer(bytes: ByteArray): DataOutputStream =
        DataOutputStream(ByteArrayOutputStream().also { it.write(bytes) })

    fun byteBuffer(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes)
}
