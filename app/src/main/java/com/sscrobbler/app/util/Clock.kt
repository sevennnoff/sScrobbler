package com.sscrobbler.app.util

interface Clock {
    fun elapsedRealtime(): Long
    fun currentTimeMillis(): Long
}

object SystemClockImpl : Clock {
    override fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
