package com.sscrobbler.app.lastfm

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LastFmClientTest {

    private fun createClientWithResponse(statusCode: Int, body: String): LastFmClient {
        val interceptor = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(statusCode)
                .message("Mock")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        val okHttp = OkHttpClient.Builder().addInterceptor(interceptor).build()
        return LastFmClient(
            apiKey = "test_key",
            apiSecret = "test_secret",
            okHttpClient = okHttp,
            baseUrl = "https://mock.last.fm/2.0/"
        )
    }

    @Test
    fun testGetToken_success() = runBlocking {
        val client = createClientWithResponse(200, """{"token":"valid_token_123"}""")
        val res = client.getToken()
        assertTrue(res is LastFmResult.Success)
        assertEquals("valid_token_123", (res as LastFmResult.Success).data)
    }

    @Test
    fun testGetToken_xmlError_doesNotThrowUnexpectedJsonToken() = runBlocking {
        val xml = """<lfm status="failed"><error code="4">Unauthorized Token - This token has not been issued</error></lfm>"""
        val client = createClientWithResponse(403, xml)
        val res = client.getToken()
        assertTrue("Should be Error, not exception", res is LastFmResult.Error)
        val err = res as LastFmResult.Error
        assertEquals(4, err.code)
        assertTrue(err.message.contains("Token not yet approved"))
    }

    @Test
    fun testGetSession_unapprovedToken_friendlyMessage() = runBlocking {
        val jsonErr = """{"message":"Unauthorized Token - This token has not been issued","error":4}"""
        val client = createClientWithResponse(403, jsonErr)
        val res = client.getSession("token123")
        assertTrue(res is LastFmResult.Error)
        val err = res as LastFmResult.Error
        assertEquals(4, err.code)
        assertTrue(err.message.contains("Token not yet approved"))
    }

    @Test
    fun testGetSession_htmlCloudflareResponse_friendlyMessage() = runBlocking {
        val html = """<!DOCTYPE html><html><head><title>Just a moment...</title></head><body>Cloudflare DDOS protection</body></html>"""
        val client = createClientWithResponse(403, html)
        val res = client.getSession("token123")
        assertTrue(res is LastFmResult.Error)
        val err = res as LastFmResult.Error
        assertTrue(err.message.contains("Cloudflare") || err.message.contains("VPN"))
    }

    @Test
    fun testGetSession_success() = runBlocking {
        val jsonSuccess = """{"session":{"name":"sevennnoff","key":"session_key_abc","subscriber":0}}"""
        val client = createClientWithResponse(200, jsonSuccess)
        val res = client.getSession("token123")
        assertTrue(res is LastFmResult.Success)
        val session = (res as LastFmResult.Success).data
        assertEquals("sevennnoff", session.name)
        assertEquals("session_key_abc", session.key)
    }
}
