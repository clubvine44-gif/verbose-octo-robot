package com.pulselens.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.view.View
import android.widget.*
import java.util.Locale
import kotlin.math.*

class MainActivity : Activity() {
    private lateinit var info: TextView
    private lateinit var graph: Graph
    private lateinit var action: Button
    private lateinit var export: Button
    private lateinit var length: Spinner
    private var cam: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    private val samples = mutableListOf<Pair<Double, Double>>()
    private var active = false
    private var startTime = 0.0
    private var lastUpdate = 0.0
    private var duration = 30
    private var qualityBad = 0
    private var frames = 0
    private fun clock() = SystemClock.elapsedRealtimeNanos() / 1e9
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 20)
            setBackgroundColor(Color.rgb(15, 22, 33))
        }
        fun text(s: String, sz: Float): TextView = TextView(this).apply {
            text = s
            textSize = sz
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 12)
        }
        root.addView(text("PulseLens — оптический пульс", 23f))
        root.addView(text("Закрой пальцем заднюю камеру и фонарик. Держи неподвижно. Не ЭКГ и не диагностика.", 14f))
        length = Spinner(this)
        length.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("30 секунд", "180 секунд"))
        root.addView(length)
        info = text("Нажми «Начать»", 18f)
        root.addView(info)
        graph = Graph(this)
        root.addView(graph, LinearLayout.LayoutParams(-1, 260))
        action = Button(this).apply {
            text = "Начать"
            setOnClickListener { if (active) stop() else { duration = if(length.selectedItemPosition == 0) 30 else 180; begin() } }
        }
        root.addView(action)
        export = Button(this).apply { text = "Поделиться CSV"; isEnabled = false; setOnClickListener { share() } }
        root.addView(export)
        setContentView(root)
    }
    private fun begin() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 11)
            return
        }
        stop()
        synchronized(samples) { samples.clear() }
        graph.values = emptyList()
        graph.invalidate()
        frames = 0
        qualityBad = 0
        startTime = clock()
        lastUpdate = 0.0
        active = true
        action.text = "Остановить"
        export.isEnabled = false
        info.text = "Запуск камеры..."
        worker = HandlerThread("ppg").also { it.start() }
        handler = Handler(worker!!.looper)
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = manager.cameraIdList.firstOrNull {
                val c = manager.getCameraCharacteristics(it)
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                  c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: throw IllegalStateException("Нет задней камеры с фонариком")
            reader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 3)
            reader!!.setOnImageAvailableListener({ r ->
                val im = try { r.acquireLatestImage() } catch (_: Exception) { null }
                if (im != null) try {
                    if (active) {
                        val plane = im.planes[2]
                        val buf = plane.buffer
                        var sum = 0.0
                        var n = 0
                        for(y in 40 until 80 step 3) for(x in 50 until 110 step 3) {
                            val offset = y * plane.rowStride + x * plane.pixelStride
                            if(offset < buf.limit()) { sum += (buf.get(offset).toInt() and 255); n++ }
                        }
                        if(n > 0) {
                            val t = clock()
                            val v = sum/n
                            synchronized(samples) { samples.add(t to v) }
                            frames++
                            if(v < 135.0 || v > 249.0) qualityBad++
                            if(t - lastUpdate > .5) {
                                lastUpdate = t
                                val copy = synchronized(samples) { samples.toList() }
                                val bad = qualityBad
                                val count = frames
                                main.post { render(copy, bad, count) }
                            }
                        }
                    }
                } finally { im.close() }
            }, handler)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if(!active) { device.close(); return }
                    cam = device
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(reader!!.surface)
                            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                        }
                        device.createCaptureSession(listOf(reader!!.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try { s.setRepeatingRequest(request.build(), null, handler) }
                                catch(e: Exception) { fail(e.message ?: "Ошибка съёмки") }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { fail("Ошибка настройки камеры") }
                        }, handler)
                    } catch(e: Exception) { fail(e.message ?: "Ошибка камеры") }
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); fail("Камера отключилась") }
                override fun onError(device: CameraDevice, error: Int) { device.close(); fail("Ошибка камеры: $error") }
            }, handler)
        } catch(e: Exception) { fail(e.message ?: "Нет доступа к камере") }
    }
    private fun fail(message: String) { main.post { stop(); info.text = message } }
    private fun render(data: List<Pair<Double,Double>>, bad: Int, count: Int) {
        if(!active) return
        val t = clock() - startTime
        val analysis = PpgAnalyzer.analyze(data)
        val unreliable = count < 80 || bad.toDouble()/count > .3 || analysis.quality < 60 || analysis.intervals.size < 5
        val intervals = analysis.intervals
        val rr = intervals.map { it * 1000.0 }
        val avg = if(rr.isNotEmpty()) rr.average() else 0.0
        val sdnn = if(rr.size > 2) sqrt(rr.map { (it-avg).pow(2) }.average()) else 0.0
        val diffs = rr.zipWithNext().map { (a,b) -> (a-b).pow(2) }
        val rmssd = if(diffs.size > 1) sqrt(diffs.average()) else 0.0
        val outliers = analysis.rejectedBeats
        val bpm = if(avg>0) 60000.0 / avg else 0.0
        val label = analysis.reason
        info.text = "Время: ${t.toInt()}/$duration с\n" +
            "Пульс: ${if(unreliable) "—" else String.format(Locale.US, "%.0f", bpm)} уд/мин\n" +
            "Ритм: $label\n" +
            "Последние интервалы: ${rr.takeLast(8).joinToString(" · ") { it.toInt().toString() }} мс\n" +
            "RMSSD: ${if(unreliable) "—" else String.format(Locale.US,"%.1f",rmssd)} мс; SDNN: ${if(unreliable) "—" else String.format(Locale.US,"%.1f",sdnn)} мс\n" +
            "Качество: ${analysis.quality}% · исключено интервалов: $outliers\n" +
            "Любые отклонения требуют подтверждения ЭКГ."
        graph.values = analysis.wave
        graph.invalidate()
        if(t>=duration) stop()
    }
    private fun stop() {
        active = false
        if(::action.isInitialized) action.text = "Начать"
        if(::export.isInitialized) export.isEnabled = synchronized(samples) { samples.isNotEmpty() }
        try { session?.stopRepeating() } catch(_: Exception) {}
        try { session?.close() } catch(_: Exception) {}
        session = null
        try { cam?.close() } catch(_: Exception) {}
        cam = null
        try { reader?.close() } catch(_: Exception) {}
        reader = null
        worker?.quitSafely()
        worker = null
        handler = null
    }
    private fun share() {
        val copy = synchronized(samples) { samples.toList() }
        if(copy.isEmpty()) return
        val csv = buildString {
            append("time_s,v_signal\n")
            val t0 = copy.first().first
            copy.forEach { append(String.format(Locale.US,"%.4f,%.3f\n",it.first-t0,it.second)) }
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type="text/plain"
            putExtra(Intent.EXTRA_TEXT,csv)
        },"Экспорт PPG"))
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==11) { if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED) begin() else info.text="Разрешение на камеру не предоставлено" }
    }
    override fun onPause() { super.onPause(); stop() }
    override fun onDestroy() { stop(); super.onDestroy() }
}
class Graph(context: android.content.Context) : View(context) {
    var values: List<Double> = emptyList()
    private val paint = Paint(3).apply { color=Color.rgb(40,195,150); style=Paint.Style.STROKE; strokeWidth=3f }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(22,38,50))
        if(values.size<2) return
        val lo=values.minOrNull() ?: return
        val hi=values.maxOrNull() ?: return
        val range=(hi-lo).coerceAtLeast(.001)
        val path=Path()
        values.forEachIndexed { i,v ->
            val x = (width*i.toFloat()/(values.size-1))
            val y = (height-12)-(height-24)*((v-lo)/range).toFloat()
            if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
        }
        canvas.drawPath(path,paint)
    }
}
