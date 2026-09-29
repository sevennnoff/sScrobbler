package com.sscrobbler.app.util

object Secrets {
    private val MASK = byteArrayOf(0x53, 0x53, 0x63, 0x72, 0x6F, 0x62, 0x62, 0x6C, 0x65, 0x72)

    private val OBF_KEY = byteArrayOf(
        101, 98, 7, 20, 12, 90, 1, 13, 81, 67,
        103, 54, 83, 71, 10, 91, 87, 92, 80, 67,
        50, 99, 83, 19, 90, 0, 6, 14, 1, 70,
        103, 48
    )

    private val OBF_SECRET = byteArrayOf(
        107, 107, 83, 64, 88, 83, 6, 95, 3, 66,
        55, 49, 7, 23, 95, 85, 91, 13, 85, 69,
        98, 101, 1, 65, 9, 4, 91, 92, 1, 70,
        55, 49
    )

    fun getApiKey(): String {
        val out = ByteArray(OBF_KEY.size)
        for (i in OBF_KEY.indices) {
            out[i] = (OBF_KEY[i].toInt() xor MASK[i % MASK.size].toInt()).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    fun getApiSecret(): String {
        val out = ByteArray(OBF_SECRET.size)
        for (i in OBF_SECRET.indices) {
            out[i] = (OBF_SECRET[i].toInt() xor MASK[i % MASK.size].toInt()).toByte()
        }
        return String(out, Charsets.UTF_8)
    }
}
