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
import android.view.HapticFeedbackConstants
import android.view.WindowInsets
import android.widget.FrameLayout
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Color
import android.util.Log
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
             or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        web = WebView(this)
        web.setBackgroundColor(Color.rgb(5, 11, 20))
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.cacheMode = WebSettings.LOAD_DEFAULT
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                publishSafeArea()
                updateMic(VoiceService.running)
                verifyLayout()
            }
        }
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Tessa")
        frame = FrameLayout(this)
        frame.setBackgroundColor(Color.rgb(7, 12, 24))
        frame.addView(web, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Android 15 enforces edge-to-edge for target 35. Keep the portrait
        // header and bottom command bar clear of system status/navigation bars.
        frame.setOnApplyWindowInsetsListener { _, insets ->
            publishSafeArea(insets)
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
        web.loadDataWithBaseURL("http://127.0.0.1:18766/", assets.open("ui.xml").bufferedReader(Charsets.UTF_8).use { it.readText() }, "text/html", "UTF-8", null)
    }
    inner class Bridge {
        @JavascriptInterface fun haptic() {
            runOnUiThread {
                if (::web.isInitialized) web.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
        @JavascriptInterface fun getConfig(): String {
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            return JSONObject().put("url", prefs.getString("url", "http://100.98.213.115:18766"))
                .put("token", prefs.getString("token", "")).toString()
        }
        @JavascriptInterface fun saveConfig(url: String, token: String) {
            val safe = url.trim().trimEnd('/')
            if (!safe.startsWith("http://") && !safe.startsWith("https://")) return
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putString("url", safe).putString("token", token)
                .remove("active_endpoint").apply()
        }
        @JavascriptInterface fun signChallenge(nonce: String): String {
            if (!Regex("[A-Za-z0-9_-]{12,64}").matches(nonce)) return ""
            val token = getSharedPreferences("settings", MODE_PRIVATE).getString("token", "").orEmpty()
            if (token.length < 20) return ""
            return try {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
                mac.doFinal(nonce.toByteArray(Charsets.US_ASCII))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            } catch (_: Exception) { "" }
        }
        @JavascriptInterface fun setActiveEndpoint(value: String) {
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val configured = prefs.getString("url", "").orEmpty()
            if (value != configured && value != "http://127.0.0.1:18766") return
            prefs.edit().putString("active_endpoint", value).apply()
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
        val configured = prefs.getString("url", "") ?: ""
        val recent = prefs.getString("active_endpoint", "").orEmpty()
        val url = if (recent == configured || recent == "http://127.0.0.1:18766") recent else configured
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
    private fun verifyLayout() {
        // Read-only diagnostics to detect clipped controls and lost orbital
        // animations on physical Android hardware after an install.
        if (!::web.isInitialized || applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val js = """
            (function() {
              const r = id => {
                const el = document.getElementById(id);
                if (!el) return null;
                const b=el.getBoundingClientRect();
                return {x:Math.round(b.x),y:Math.round(b.y),w:Math.round(b.width),h:Math.round(b.height)};
              };
              return JSON.stringify({
                viewport:[innerWidth,innerHeight],
                screen:r('screen'),
                orb:r('orb'),
                command:r('prompt'),
                animation:getComputedStyle(document.querySelector('.orbit.a')).animationName,
                pixelRatio:devicePixelRatio,
                overflowX:document.documentElement.scrollWidth>innerWidth,
                overflowY:document.documentElement.scrollHeight>innerHeight
              });
            })()
        """.trimIndent()
        web.evaluateJavascript(js) { value ->
            Log.i("TESSA_LAYOUT", (value ?: "null").take(1000))
        }
    }
    @Suppress("DEPRECATION")
    private fun publishSafeArea(insets: android.view.WindowInsets? = if (::frame.isInitialized) frame.rootWindowInsets else null) {
        if (!::web.isInitialized || insets == null) return
        val top: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
            top = insets.getInsets(WindowInsets.Type.statusBars()).top
            bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else {
            top = insets.systemWindowInsetTop
            bottom = insets.systemWindowInsetBottom
        }
        val density = resources.displayMetrics.density
        val topDp = if (landscape) 0 else (top / density).toInt()
        val bottomDp = if (landscape) 0 else (bottom / density).toInt()
        web.post {
            web.evaluateJavascript(
                "document.documentElement.style.setProperty('--safe-top','" + topDp + "px');" +
                "document.documentElement.style.setProperty('--safe-bottom','" + bottomDp + "px');", null
            )
        }
    }
    private fun setFullscreen(on: Boolean) {
        landscape = on
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (on) {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        } else {
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
        if (::frame.isInitialized) {
            frame.requestApplyInsets()
            publishSafeArea()
        }
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
            updateMic(VoiceService.running)
        }
    }
    override fun onPause() {
        // Keep the single WebView, its compositor and JS connection intact.
        super.onPause()
    }
    private fun handleBackPressed() {
        if (landscape) {
            setFullscreen(false)
            return
        }
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
