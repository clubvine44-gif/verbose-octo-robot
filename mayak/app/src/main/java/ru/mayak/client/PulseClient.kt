package ru.mayak.client

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small control-plane client. Pulse only returns MAYAK-owned/approved gateways;
 * user traffic never passes through this request.
 */
object PulseClient {
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000

    data class Gateway(
        val id: String,
        val host: String,
        val port: Int,
        val region: String,
        val enabled: Boolean,
    )

    fun fetch(directoryUrl: String): List<Gateway> {
        val connection = (URL(directoryUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Pulse HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parse(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String): List<Gateway> {
        val root = org.json.JSONObject(body)
        val nodes = root.optJSONArray("nodes") ?: JSONArray()
        val result = ArrayList<Gateway>(nodes.length())
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            val host = node.optString("host").trim()
            val port = node.optInt("port", 443)
            if (host.isBlank() || port !in 1..65535) continue
            result += Gateway(
                id = node.optString("id", "node-$i"),
                host = host,
                port = port,
                region = node.optString("region", ""),
                enabled = node.optBoolean("enabled", true),
            )
        }
        return result.filter { it.enabled }
    }
}
