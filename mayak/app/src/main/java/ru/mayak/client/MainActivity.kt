package ru.mayak.client

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var button: Button
    private lateinit var relayHost: EditText
    private lateinit var relayPort: EditText
    private lateinit var relayToken: EditText
    private lateinit var tokenStore: TokenStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        button = findViewById(R.id.toggle)
        relayHost = findViewById(R.id.relayHost)
        relayPort = findViewById(R.id.relayPort)
        relayToken = findViewById(R.id.relayToken)
        tokenStore = TokenStore(this)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        relayHost.setText(prefs.getString(KEY_HOST, ""))
        relayPort.setText(prefs.getString(KEY_PORT, "443"))
        relayToken.setText(tokenStore.get())
        button.setOnClickListener { toggle() }
    }

    private fun toggle() {
        if (MayakVpnService.running.get()) {
            stopService(Intent(this, MayakVpnService::class.java))
            status.text = "Статус: выключен"
            button.text = "Запустить MAYAK"
            return
        }
        val host = relayHost.text.toString().trim()
        val port = relayPort.text.toString().trim().toIntOrNull() ?: 443
        val token = relayToken.text.toString().trim()
        if (host.isBlank() || port !in 1..65535 || token.isBlank()) {
            status.text = "Укажите relay host, port и access token"
            return
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_HOST, host)
            .putString(KEY_PORT, port.toString())
            .apply()
        tokenStore.put(token)
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingHost = host
            pendingPort = port
            pendingToken = token
            startActivityForResult(intent, REQUEST_VPN)
        } else {
            startVpn(host, port, token)
        }
    }

    private fun startVpn(host: String, port: Int, token: String) {
        val intent = Intent(this, MayakVpnService::class.java)
            .putExtra(MayakVpnService.EXTRA_RELAY_HOST, host)
            .putExtra(MayakVpnService.EXTRA_RELAY_PORT, port)
            .putExtra(MayakVpnService.EXTRA_RELAY_TOKEN, token)
        startService(intent)
        status.text = "Статус: подключение к relay..."
        button.text = "Остановить MAYAK"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            startVpn(pendingHost, pendingPort, pendingToken)
        }
    }

    companion object {
        private const val REQUEST_VPN = 42
        private const val PREFS = "mayak"
        private const val KEY_HOST = "relay_host"
        private const val KEY_PORT = "relay_port"
        private var pendingHost = ""
        private var pendingPort = 443
        private var pendingToken = ""
    }
}
