package ru.mayak.client.transport

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class MayakTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 10_000,
    private val protectSocket: (Socket) -> Boolean = { true }
) : Closeable {
    private var socket: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Synchronized
    fun connect() {
        if (socket?.isConnected == true && socket?.isClosed == false) return
        val factory = SSLContext.getDefault().socketFactory
        val raw = factory.createSocket() as SSLSocket
        check(protectSocket(raw)) { "Relay socket could not be protected from the VPN" }
        raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
        val supported = raw.supportedProtocols.toSet()
        raw.enabledProtocols = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }.toTypedArray()
        raw.startHandshake()
        socket = raw
        input = raw.inputStream
        output = raw.outputStream
    }

    @Synchronized
    fun sendIpPacket(packet: ByteArray) {
        val out = output ?: throw IllegalStateException("Transport is not connected")
        TransportFrame.write(out, TransportFrame.TYPE_IP, packet)
    }

    fun receive(): TransportFrame.Frame {
        val input = input ?: throw IllegalStateException("Transport is not connected")
        return TransportFrame.read(input)
    }

    override fun close() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        input = null
        output = null
    }
}
