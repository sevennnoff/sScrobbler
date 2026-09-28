package com.sscrobbler.app.lastfm

import java.security.MessageDigest

object LastFmSigner {
    fun sign(params: Map<String, String>, secret: String): String {
        val sortedFiltered = params.entries
            .filter { it.key != "format" && it.key != "callback" }
            .sortedBy { it.key }
            .joinToString(separator = "") { "${it.key}${it.value}" }
        return md5(sortedFiltered + secret)
    }

    fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
