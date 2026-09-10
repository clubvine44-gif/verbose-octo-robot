package ru.mayak.client.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Length-delimited MAYAK transport frame. Encryption is provided by the TLS session. */
object TransportFrame {
    private const val MAGIC = 0x4D594B31 // "MYK1"
    const val VERSION: Byte = 1
    const val TYPE_IP: Byte = 1
    const val TYPE_CLOSE: Byte = 2
    private const val MAX_PAYLOAD = 65535

    data class Frame(val type: Byte, val payload: ByteArray)

    fun write(output: OutputStream, type: Byte, payload: ByteArray) {
        require(payload.size <= MAX_PAYLOAD) { "Payload too large: ${payload.size}" }
        val out = DataOutputStream(output)
        out.writeInt(MAGIC)
        out.writeByte(VERSION.toInt())
        out.writeByte(type.toInt())
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    fun read(input: InputStream): Frame {
        val data = DataInputStream(input)
        val magic = data.readInt()
        if (magic != MAGIC) throw IllegalStateException("Invalid MAYAK frame magic")
        val version = data.readUnsignedByte()
        if (version != VERSION.toInt()) throw IllegalStateException("Unsupported MAYAK frame version: $version")
        val type = data.readByte()
        val length = data.readInt()
        if (length < 0 || length > MAX_PAYLOAD) throw IllegalStateException("Invalid MAYAK frame length: $length")
        val payload = ByteArray(length)
        data.readFully(payload)
        return Frame(type, payload)
    }
}
