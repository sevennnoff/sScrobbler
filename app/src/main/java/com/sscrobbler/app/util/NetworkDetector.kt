package com.sscrobbler.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

interface NetworkDetector {
    fun isOnline(): Boolean
}

class SystemNetworkDetector(private val context: Context) : NetworkDetector {
    override fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
