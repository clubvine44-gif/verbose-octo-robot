package ru.mayak.client.pulse

data class PulseNode(
    val id: String,
    val country: String,
    val endpoint: String,
    val port: Int,
    val transport: String,
    val enabled: Boolean,
    val weight: Int = 0
)
