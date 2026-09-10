package ru.mayak.client.router

/**
 * Minimal packet policy for Transport Engine v0.2.
 * The VPN interface currently installs an IPv4 default route, so IPv4 packets
 * are forwarded to the encrypted transport. Non-IPv4 packets are rejected
 * until the IPv6 path is implemented rather than being silently leaked.
 */
class TrafficRouter {
    data class Decision(val forward: Boolean, val reason: String)

    fun classify(packet: ByteArray): Decision {
        if (packet.isEmpty()) return Decision(false, "empty")
        val version = (packet[0].toInt() ushr 4) and 0x0f
        return when (version) {
            4 -> Decision(true, "ipv4")
            6 -> Decision(false, "ipv6-not-supported")
            else -> Decision(false, "unknown-ip-version")
        }
    }
}
