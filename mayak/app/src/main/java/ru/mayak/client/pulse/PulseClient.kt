package ru.mayak.client.pulse

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PulseClient(private val directoryUrl: String) {
    fun fetch(timeoutMs: Int = 7000): List<PulseNode> {
        val connection = (URL(directoryUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
        }
        return try {
            if (connection.responseCode !in 200..299) return emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String): List<PulseNode> {
        val root = JSONObject(body)
        if (root.optInt("version", -1) != 1) return emptyList()
        val array = root.optJSONArray("nodes") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val node = array.optJSONObject(i) ?: continue
                val id = node.optString("id").trim()
                val country = node.optString("country").trim().uppercase()
                val endpoint = node.optString("endpoint").trim()
                val port = node.optInt("port", 0)
                val transport = node.optString("transport").trim().lowercase()
                if (id.isNotEmpty() && country.length == 2 && endpoint.isNotEmpty() && port in 1..65535 && transport == "tls" && node.optBoolean("enabled", false)) {
                    add(PulseNode(id, country, endpoint, port, transport, true, node.optInt("weight", 0)))
                }
            }
        }
    }
}
