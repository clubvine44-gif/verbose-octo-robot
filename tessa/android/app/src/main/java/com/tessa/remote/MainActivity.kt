package com.tessa.remote

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var frame: FrameLayout
    private var systemBackCallback: OnBackInvokedCallback? = null
    private var landscape = false
    private val micCode = 709
    private var voiceStartPending = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = android.graphics.Color.rgb(5, 10, 23)
        window.navigationBarColor = android.graphics.Color.rgb(5, 10, 23)
        web = WebView(this)
        web.setBackgroundColor(android.graphics.Color.rgb(5, 10, 23))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.cacheMode = WebSettings.LOAD_DEFAULT
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Tessa")
        frame = FrameLayout(this)
        frame.addView(web, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Android 15 enforces edge-to-edge for target 35. Keep the portrait
        // header and bottom command bar clear of system status/navigation bars.
        frame.setOnApplyWindowInsetsListener { view, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.statusBars()).top
            else insets.systemWindowInsetTop
            val bottom = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            else insets.systemWindowInsetBottom
            view.setPadding(0, if (landscape) 0 else top,
                0, if (landscape) 0 else bottom)
            insets
        }
        setContentView(frame)
        frame.requestApplyInsets()
        if (Build.VERSION.SDK_INT >= 33) {
            systemBackCallback = OnBackInvokedCallback { handleBackPressed() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, systemBackCallback!!
            )
        }
        web.loadDataWithBaseURL("file:///android_asset/", assets.open("ui.xml").bufferedReader(Charsets.UTF_8).use { it.readText() }, "text/html", "UTF-8", null)
    }
    inner class Bridge {
        @JavascriptInterface fun getConfig(): String {
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            return JSONObject().put("url", prefs.getString("url", "http://192.168.1.2:8766"))
                .put("token", prefs.getString("token", "")).toString()
        }
        @JavascriptInterface fun saveConfig(url: String, token: String) {
            val safe = url.trim().trimEnd('/')
            if (!safe.startsWith("http://") && !safe.startsWith("https://")) return
            getSharedPreferences("settings", MODE_PRIVATE).edit().putString("url", safe).putString("token", token).apply()
        }
        @JavascriptInterface fun fullscreen(enabled: Boolean) {
            runOnUiThread { setFullscreen(enabled) }
        }
        @JavascriptInterface fun isVoiceRunning(): Boolean = VoiceService.running
        @JavascriptInterface fun toggleVoice(): Boolean {
            if (VoiceService.running) {
                stopService(Intent(this@MainActivity, VoiceService::class.java))
                updateMic(false)
                return false
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                voiceStartPending = true
                runOnUiThread { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), micCode) }
                return false
            }
            startVoice()
            return true
        }
    }
    private fun startVoice() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val url = prefs.getString("url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (url.isBlank() || token.isBlank()) {
            showToast("Сначала укажи адрес ПК и ключ", false)
            return
        }
        val intent = Intent(this, VoiceService::class.java)
            .putExtra("url", url).putExtra("token", token)
        try {
            startForegroundService(intent)
            updateMic(true)
        } catch (e: Exception) {
            showToast("Микрофон: " + (e.message ?: "ошибка запуска"), false)
        }
    }
    private fun updateMic(active: Boolean) {
        runOnUiThread { web.evaluateJavascript("window.setMicState && window.setMicState(" + active + ")", null) }
    }
    private fun showToast(msg: String, ok: Boolean) {
        runOnUiThread { web.evaluateJavascript("window.flash && window.flash(" + JSONObject.quote(msg) + "," + ok + ")", null) }
    }
    private fun setFullscreen(on: Boolean) {
        landscape = on
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (on) {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
        if (::frame.isInitialized) frame.requestApplyInsets()
        web.evaluateJavascript("window.setLandscape && window.setLandscape(" + on + ")", null)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (landscape) {
            web.evaluateJavascript("window.setLandscape && window.setLandscape(true)", null)
        }
    }
    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) {
            web.onResume()
            updateMic(VoiceService.running)
        }
    }
    override fun onPause() {
        if (::web.isInitialized) web.onPause()
        super.onPause()
    }
    private fun handleBackPressed() {
        if (landscape) { setFullscreen(false); return }
        web.evaluateJavascript("window.dismissOverlay && window.dismissOverlay()", null)
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackPressed()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == micCode && voiceStartPending) {
            voiceStartPending = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoice()
            else showToast("Нет доступа к микрофону", false)
        }
    }
    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) systemBackCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        web.destroy()
        super.onDestroy()
    }
}
