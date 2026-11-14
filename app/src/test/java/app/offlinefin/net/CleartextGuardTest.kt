package app.offlinefin.net

import app.offlinefin.core.AppError
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CleartextGuardTest {
    private val server = MockWebServer().apply { start() }

    @After fun stop() = server.shutdown()

    private fun client(allow: Boolean) = OkHttpClient.Builder().addInterceptor(CleartextGuard { allow }).build()

    @Test fun `http is blocked and nothing reaches the server`() {
        server.enqueue(MockResponse())
        try {
            client(false).newCall(Request.Builder().url(server.url("/")).build()).execute()
            fail("expected a blocked request")
        } catch (e: BlockedRequestException) {
            assertEquals(AppError.Kind.CLEARTEXT_BLOCKED, e.error.kind)
            assertEquals(AppError.Kind.CLEARTEXT_BLOCKED, AppError.from(e).kind)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `http is allowed when opted in`() {
        server.enqueue(MockResponse().setBody("ok"))
        client(true).newCall(Request.Builder().url(server.url("/")).build()).execute().use {
            assertEquals("ok", it.body.string())
        }
    }

    @Test fun `the block is reported as CLEARTEXT_BLOCKED through enqueue too`() {
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        client(false).newCall(Request.Builder().url(server.url("/")).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { failure.set(e); done.countDown() }
            override fun onResponse(call: Call, response: Response) { response.close(); done.countDown() }
        })
        assertTrue("callback not invoked", done.await(5, TimeUnit.SECONDS))
        assertEquals(AppError.Kind.CLEARTEXT_BLOCKED, AppError.from(failure.get()).kind)
    }

    @Test fun `a redirect from https to http is blocked by the network interceptor`() {
        val held = HeldCertificate.Builder().addSubjectAlternativeName(server.hostName).build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val secure = MockWebServer().apply { useHttps(serverCerts.sslSocketFactory(), false); start() }
        try {
            secure.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/plain").toString()))
            server.enqueue(MockResponse())
            val client = OkHttpClient.Builder()
                .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
                .addNetworkInterceptor(CleartextGuard { false })
                .build()
            try {
                client.newCall(Request.Builder().url(secure.url("/start")).build()).execute()
                fail("expected a blocked request")
            } catch (e: IOException) {
                assertEquals(AppError.Kind.CLEARTEXT_BLOCKED, AppError.from(e).kind)
            }
            assertEquals(1, secure.requestCount)
            assertEquals(0, server.requestCount)
        } finally {
            secure.shutdown()
        }
    }
}
