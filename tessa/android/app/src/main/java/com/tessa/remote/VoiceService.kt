package com.tessa.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VoiceService : Service() {
    companion object { @Volatile var running: Boolean = false }
    private val active = AtomicBoolean(false)
    private val client by lazy { OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build() }
    private var worker: Thread? = null
    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (active.getAndSet(true)) return START_STICKY
        val url = intent?.getStringExtra("url") ?: ""
        val token = intent?.getStringExtra("token") ?: ""
        if (token.isBlank() || url.isBlank()) { stopSelf(); return START_NOT_STICKY }
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(NotificationChannel("voice", "Прослушивание TESSA", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "voice")
            .setContentTitle("TESSA слушает")
            .setContentText("Нажми на TESSA, чтобы остановить")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(83, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(83, notification)
        running = true
        worker = Thread({ capture(url, token) }, "TessaMic").also { it.start() }
        return START_STICKY
    }
    private fun capture(base: String, token: String) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { stopSelf(); return }
        try {
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 8192))
            recorder = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) { stopSelf(); return }
            rec.startRecording()
            val bytes = ByteArray(2048)
            var backoff = 500L
            while (active.get()) {
                val opened = AtomicBoolean(false)
                val failed = AtomicBoolean(false)
                var ws: WebSocket? = null
                try {
                    val endpoint = base.replaceFirst("https://", "wss://")
                        .replaceFirst("http://", "ws://").trimEnd('/') + "/audio?token=" +
                        java.net.URLEncoder.encode(token, "UTF-8")
                    ws = client.newWebSocket(Request.Builder().url(endpoint).build(), object: WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            opened.set(true)
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            failed.set(true)
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            failed.set(true)
                        }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            failed.set(true)
                        }
                    })
                    socket = ws
                    // Drain the microphone until the WebSocket is really open, but
                    // never queue raw PCM during a failed connection.
                    val openDeadline = SystemClock.elapsedRealtime() + 7000L
                    while (active.get() && !opened.get() && !failed.get() &&
                           SystemClock.elapsedRealtime() < openDeadline) {
                        rec.read(bytes, 0, bytes.size, AudioRecord.READ_BLOCKING)
                    }
                    if (!opened.get() || failed.get()) throw IllegalStateException("Audio transport unavailable")
                    backoff = 500L
                    var dropped = 0
                    while (active.get() && !failed.get() && dropped < 35) {
                        val n = rec.read(bytes, 0, bytes.size, AudioRecord.READ_BLOCKING)
                        if (n > 0) {
                            // Drop stale chunks instead of building seconds of lag.
                            if (ws.queueSize() > 192 * 1024 || !ws.send(bytes.copyOf(n).toByteString())) {
                                dropped++
                            } else {
                                dropped = 0
                            }
                        } else if (n < 0) {
                            throw IllegalStateException("AudioRecord read failed: " + n)
                        }
                    }
                } catch (_: Exception) {
                    // Network can disappear; foreground recording stays on and retries.
                } finally {
                    ws?.close(1000, "reconnect")
                    socket = null
                }
                if (active.get()) {
                    SystemClock.sleep(backoff)
                    backoff = (backoff * 2).coerceAtMost(12000)
                }
            }
        } catch (_: Exception) {
            // AudioRecord permission or hardware failure; safely release.
        } finally {
            try { recorder?.stop() } catch (_: Exception) {}
            recorder?.release()
            recorder = null
            running = false
            active.set(false)
            stopSelf()
        }
    }
    override fun onDestroy() {
        active.set(false)
        socket?.close(1000, "stopped")
        try { recorder?.stop() } catch (_: Exception) {}
        worker?.interrupt()
        running = false
        super.onDestroy()
    }
}
