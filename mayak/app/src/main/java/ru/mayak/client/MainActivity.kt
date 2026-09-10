package ru.mayak.client

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var statusDetail: TextView
    private lateinit var button: Button
    private lateinit var settings: View
    private lateinit var relayHost: EditText
    private lateinit var relayPort: EditText
    private lateinit var relayToken: EditText
    private lateinit var tokenStore: TokenStore
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        statusDetail = findViewById(R.id.statusDetail)
        button = findViewById(R.id.toggle)
        settings = findViewById(R.id.settingsPanel)
        relayHost = findViewById(R.id.relayHost)
        relayPort = findViewById(R.id.relayPort)
        relayToken = findViewById(R.id.relayToken)
        tokenStore = TokenStore(this)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        relayHost.setText(prefs.getString(KEY_HOST, ""))
        relayPort.setText(prefs.getString(KEY_PORT, "443"))
        relayToken.setText(tokenStore.get())

        findViewById<View>(R.id.settingsToggle).setOnClickListener {
            settings.visibility = if (settings.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        button.setOnClickListener { toggle() }
        renderStopped()
    }

    private fun toggle() {
        if (MayakVpnService.running.get()) {
            stopService(Intent(this, MayakVpnService::class.java))
            renderStopped()
            return
        }

        val token = relayToken.text.toString().trim()
        if (token.isBlank()) {
            status.text = "Нужен доступ"
            statusDetail.text = "Откройте настройки один раз и добавьте ключ MAYAK."
            settings.visibility = View.VISIBLE
            return
        }

        val manualHost = relayHost.text.toString().trim()
        val manualPort = relayPort.text.toString().trim().toIntOrNull() ?: 443
        saveSettings(manualHost, manualPort, token)
        status.text = "Поиск маршрута..."
        statusDetail.text = "MAYAK выбирает доступную точку выхода"
        button.text = "Остановить"
        button.isEnabled = false

        executor.execute {
            val gateway = try {
                PulseClient.fetch(PULSE_DIRECTORY_URL).firstOrNull()
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                button.isEnabled = true
                val host = gateway?.host?.takeIf { it.isNotBlank() } ?: manualHost
                val port = gateway?.port ?: manualPort
                if (host.isBlank()) {
                    status.text = "Маршрут не найден"
                    statusDetail.text = "Добавьте свой gateway в настройках или Pulse directory."
                    button.text = "Подключиться"
                    return@runOnUiThread
                }
                startVpn(host, port, token)
            }
        }
    }

    private fun saveSettings(host: String, port: Int, token: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_HOST, host)
            .putString(KEY_PORT, port.toString())
            .apply()
        tokenStore.put(token)
    }

    private fun startVpn(host: String, port: Int, token: String) {
        val intent = Intent(this, MayakVpnService::class.java)
            .putExtra(MayakVpnService.EXTRA_RELAY_HOST, host)
            .putExtra(MayakVpnService.EXTRA_RELAY_PORT, port)
            .putExtra(MayakVpnService.EXTRA_RELAY_TOKEN, token)
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingHost = host
            pendingPort = port
            pendingToken = token
            startActivityForResult(prepareIntent, REQUEST_VPN)
        } else {
            startService(intent)
            status.text = "Подключено"
            statusDetail.text = "Маршрут: $host:$port"
        }
    }

    private fun renderStopped() {
        status.text = "Не подключено"
        statusDetail.text = "Один раз настройте доступ — дальше MAYAK работает автоматически"
        button.text = "Подключиться"
        button.isEnabled = true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN) return
        if (resultCode == RESULT_OK) {
            startService(Intent(this, MayakVpnService::class.java)
                .putExtra(MayakVpnService.EXTRA_RELAY_HOST, pendingHost)
                .putExtra(MayakVpnService.EXTRA_RELAY_PORT, pendingPort)
                .putExtra(MayakVpnService.EXTRA_RELAY_TOKEN, pendingToken))
            status.text = "Подключено"
            statusDetail.text = "MAYAK использует выбранный маршрут"
        } else {
            renderStopped()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_VPN = 42
        private const val PREFS = "mayak"
        private const val KEY_HOST = "relay_host"
        private const val KEY_PORT = "relay_port"
        private const val PULSE_DIRECTORY_URL = "https://raw.githubusercontent.com/clubvine44-gif/verbose-octo-robot/main/mayak/pulse/directory.json"
        private var pendingHost = ""
        private var pendingPort = 443
        private var pendingToken = ""
    }
}
