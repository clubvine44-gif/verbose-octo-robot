package ru.mayak.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import ru.mayak.client.router.TrafficRouter
import ru.mayak.client.transport.MayakTransport
import ru.mayak.client.transport.TransportFrame
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MayakVpnService : VpnService() {
    private var vpn: ParcelFileDescriptor? = null
    private var packetThread: Thread? = null
    private var receiveThread: Thread? = null
    private var transport: MayakTransport? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.get()) return START_STICKY
        startForeground(NOTIFICATION_ID, notification())

        val host = intent?.getStringExtra(EXTRA_RELAY_HOST).orEmpty()
        val port = intent?.getIntExtra(EXTRA_RELAY_PORT, DEFAULT_RELAY_PORT) ?: DEFAULT_RELAY_PORT
        val token = intent?.getStringExtra(EXTRA_RELAY_TOKEN).orEmpty()
        if (host.isBlank() || token.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        vpn = Builder().setSession("MAYAK").setBlocking(true).setMtu(1500)
            .addAddress("10.7.0.2", 32).addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1").establish()
        if (vpn == null) return START_NOT_STICKY

        try {
            transport = MayakTransport(host, port, token, protectSocket = ::protect).also { it.connect() }
        } catch (_: Exception) {
            transport?.close()
            transport = null
            vpn?.close()
            vpn = null
            stopSelf(startId)
            return START_NOT_STICKY
        }

        running.set(true)
        packetThread = Thread { packetLoop() }.also { it.start() }
        receiveThread = Thread { receiveLoop() }.also { it.start() }
        return START_STICKY
    }

    private fun packetLoop() {
        val fd = vpn ?: return
        val inputFd = try { ParcelFileDescriptor.dup(fd.fileDescriptor) } catch (_: Exception) { return }
        val input = FileInputStream(inputFd.fileDescriptor)
        val buffer = ByteArray(32767)
        val router = TrafficRouter()
        try {
            while (running.get()) {
                val n = input.read(buffer)
                if (n <= 0) continue
                val packet = buffer.copyOf(n)
                if (router.classify(packet).forward) {
                    transport?.sendIpPacket(packet)
                    packets.incrementAndGet()
                }
            }
        } catch (_: Exception) {
            if (running.get()) stopSelf()
        } finally {
            try { input.close() } catch (_: Exception) { }
            try { inputFd.close() } catch (_: Exception) { }
        }
    }

    private fun receiveLoop() {
        val fd = vpn ?: return
        val outputFd = try { ParcelFileDescriptor.dup(fd.fileDescriptor) } catch (_: Exception) { return }
        val output = FileOutputStream(outputFd.fileDescriptor)
        try {
            while (running.get()) {
                val frame = transport?.receive() ?: break
                if (frame.type == TransportFrame.TYPE_IP && frame.payload.isNotEmpty()) {
                    output.write(frame.payload)
                    output.flush()
                    receivedPackets.incrementAndGet()
                }
            }
        } catch (_: Exception) {
            if (running.get()) stopSelf()
        } finally {
            try { output.close() } catch (_: Exception) { }
            try { outputFd.close() } catch (_: Exception) { }
        }
    }

    private fun notification(): android.app.Notification {
        val channelId = "mayak"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "MAYAK", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId).setContentTitle("MAYAK")
            .setContentText("Зашифрованный транспорт запущен")
            .setSmallIcon(android.R.drawable.stat_sys_warning).setContentIntent(intent).setOngoing(true).build()
    }

    override fun onDestroy() {
        running.set(false)
        packetThread?.interrupt()
        receiveThread?.interrupt()
        transport?.close()
        transport = null
        vpn?.close()
        vpn = null
        super.onDestroy()
    }

    override fun onRevoke() { stopSelf(); super.onRevoke() }

    companion object {
        const val EXTRA_RELAY_HOST = "ru.mayak.client.RELAY_HOST"
        const val EXTRA_RELAY_PORT = "ru.mayak.client.RELAY_PORT"
        const val EXTRA_RELAY_TOKEN = "ru.mayak.client.RELAY_TOKEN"
        const val DEFAULT_RELAY_PORT = 443
        const val NOTIFICATION_ID = 701
        val running = AtomicBoolean(false)
        val packets = AtomicLong(0)
        val receivedPackets = AtomicLong(0)
    }
}
