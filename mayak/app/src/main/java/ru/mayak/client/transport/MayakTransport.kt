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
    private val authToken: String,
    private val connectTimeoutMs: Int = 10_000,
    private val protectSocket: (Socket) -> Boolean = { true }
) : Closeable {
    private var socket: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Synchronized
    fun connect() {
        if (socket?.isConnected == true && socket?.isClosed == false) return
        require(authToken.isNotBlank()) { "Relay access token is required" }
        val factory = SSLContext.getDefault().socketFactory
        val raw = factory.createSocket() as SSLSocket
        try {
            check(protectSocket(raw)) { "Relay socket could not be protected from the VPN" }
            raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
            val supported = raw.supportedProtocols.toSet()
            raw.enabledProtocols = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }.toTypedArray()
            raw.sslParameters = raw.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            raw.startHandshake()
            socket = raw
            input = raw.inputStream
            output = raw.outputStream
            TransportFrame.write(raw.outputStream, TransportFrame.TYPE_AUTH, authToken.toByteArray(Charsets.UTF_8))
            val reply = TransportFrame.read(raw.inputStream)
            check(reply.type == TransportFrame.TYPE_AUTH && reply.payload.contentEquals(AUTH_OK)) {
                "Relay authentication failed"
            }
        } catch (e: Exception) {
            try { raw.close() } catch (_: Exception) { }
            socket = null
            input = null
            output = null
            throw e
        }
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

    companion object {
        private val AUTH_OK = "OK".toByteArray(Charsets.UTF_8)
    }
}
