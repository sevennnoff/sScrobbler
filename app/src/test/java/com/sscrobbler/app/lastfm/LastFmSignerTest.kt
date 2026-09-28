package com.sscrobbler.app.lastfm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class LastFmSignerTest {

    @Test
    fun testSignCalculatesMd5OfAlphabeticallySortedParameters() {
        val params = mapOf(
            "track" to "Song Title",
            "artist" to "Artist Name",
            "api_key" to "my_api_key",
            "method" to "track.scrobble",
            "timestamp" to "1700000000"
        )
        val secret = "my_secret"

        // Sorted:
        // api_key: my_api_key
        // artist: Artist Name
        // method: track.scrobble
        // timestamp: 1700000000
        // track: Song Title
        val expectedRaw = "api_keymy_api_keyartistArtist Namemethodtrack.scrobbletimestamp1700000000trackSong Titlemy_secret"
        val expectedMd5 = MessageDigest.getInstance("MD5")
            .digest(expectedRaw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val actualSig = LastFmSigner.sign(params, secret)
        assertEquals(expectedMd5, actualSig)
    }

    @Test
    fun testSignExcludesFormatAndCallback() {
        val params = mapOf(
            "artist" to "Cher",
            "track" to "Believe",
            "format" to "json",
            "callback" to "handleResult"
        )
        val secret = "secret123"

        val expectedRaw = "artistChertrackBelievesecret123"
        val expectedMd5 = MessageDigest.getInstance("MD5")
            .digest(expectedRaw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val actualSig = LastFmSigner.sign(params, secret)
        assertEquals(expectedMd5, actualSig)
    }
}
