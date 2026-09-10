package ru.mayak.client

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        button = findViewById(R.id.toggle)
        button.setOnClickListener { toggle() }
    }

    private fun toggle() {
        if (MayakVpnService.running) {
            stopService(Intent(this, MayakVpnService::class.java))
            status.text = "Статус: выключен"
            button.text = "Запустить MAYAK"
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, REQUEST_VPN) else startVpn()
    }

    private fun startVpn() {
        startService(Intent(this, MayakVpnService::class.java))
        status.text = "Статус: локальный транспорт запущен"
        button.text = "Остановить MAYAK"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) startVpn()
    }

    companion object { private const val REQUEST_VPN = 42 }
}
