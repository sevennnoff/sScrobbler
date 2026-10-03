package com.sscrobbler.app.util

import org.junit.Test
import org.junit.Assert.*

class SecretsTest {
    @Test
    fun testSecrets() {
        val key = Secrets.getApiKey()
        val secret = Secrets.getApiSecret()
        println("KEY: ")
        println("SECRET: ")
        assertEquals("61dfc8ca414e05e95051a00a5bdbd44c", key)
        assertEquals("880271d3f0dbde079a0716b3ff90d4db", secret)
    }
}
