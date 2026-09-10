package ru.mayak.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MayakVpnService : VpnService() {
    private var vpn: ParcelFileDescriptor? = null
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.get()) return START_STICKY
        startForeground(NOTIFICATION_ID, notification())
        vpn = Builder().setSession("MAYAK").setBlocking(true).setMtu(1500)
            .addAddress("10.7.0.2", 32).addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1").establish()
        if (vpn == null) return START_NOT_STICKY
        running.set(true)
        worker = Thread { readLoop() }.also { it.start() }
        return START_STICKY
    }

    private fun notification(): android.app.Notification {
        val channelId = "mayak"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "MAYAK", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId).setContentTitle("MAYAK")
            .setContentText("Локальный транспорт запущен").setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(intent).setOngoing(true).build()
    }

    private fun readLoop() {
        val fd = vpn ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val buffer = ByteArray(32767)
        try { while (running.get()) { val n = input.read(buffer); if (n > 0) packets.incrementAndGet() } }
        catch (_: Exception) { }
        finally { try { input.close() } catch (_: Exception) {} }
    }

    override fun onDestroy() { running.set(false); worker?.interrupt(); vpn?.close(); vpn = null; super.onDestroy() }
    override fun onRevoke() { stopSelf(); super.onRevoke() }

    companion object { const val NOTIFICATION_ID = 701; val running = AtomicBoolean(false); val packets = AtomicLong(0) }
}
