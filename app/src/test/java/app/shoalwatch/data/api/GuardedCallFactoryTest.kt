package app.shoalwatch.data.api

import app.shoalwatch.core.AppError
import app.shoalwatch.net.ServerAddress
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * The guard's authority rule on the forms a host can take, without a network: the delegate only records what would be sent.
 * - IPv6 literal: compared in OkHttp's canonical form, so `[0:0:0:0:0:0:0:1]` is the route's `[::1]`; another address is refused.
 * - trailing-dot host: `media.example.` is a different name to the resolver and to TLS, so it is refused.
 * - explicit default port: `:80` on an http address (or `:443` on https) IS the same authority (OkHttp drops it), so it passes.
 */
class GuardedCallFactoryTest {
    private val sent = mutableListOf<Request>()
    /** The account's client, as far as this test needs: a call that records its request when executed, and sends nothing. */
    private val delegate = Call.Factory { request ->
        object : Call by OkHttpClient().newCall(request) {
            override fun execute(): Response { sent += request; throw IOException("recorded, not sent") }
        }
    }

    private fun guard(base: String): GuardedCallFactory {
        val url = base.toHttpUrl()
        return GuardedCallFactory(delegate, ServerAddress.authorityOf(url), "/videos", url.scheme) { null }
    }

    private fun passes(g: GuardedCallFactory, url: String) {
        try {
            g.newCall(Request.Builder().url(url).build()).execute().close()
        } catch (_: IOException) {
        }
        assertEquals(url, 1, sent.size)
        sent.clear()
    }

    private fun refused(g: GuardedCallFactory, url: String) {
        val call = g.newCall(Request.Builder().url(url).build())
        try {
            call.execute().close()
            fail("expected a refusal: $url")
        } catch (e: IOException) {
            assertEquals(url, AppError.Kind.FORBIDDEN, AppError.from(e).kind)
        }
        assertTrue("nothing may reach the client: $url", sent.isEmpty())
    }

    @Test fun anIpv6LiteralIsTheSameAuthorityOnlyInAnyOfItsSpellingsOfTheSameAddress() {
        val g = guard("http://[::1]:8096")
        passes(g, "http://[::1]:8096/Videos/x/stream")
        passes(g, "http://[0:0:0:0:0:0:0:1]:8096/Videos/x/stream")
        refused(g, "http://[::2]:8096/Videos/x/stream")
        refused(g, "http://[::1]:8097/Videos/x/stream")
        refused(g, "http://127.0.0.1:8096/Videos/x/stream")
    }

    @Test fun aTrailingDotHostIsAnotherHost() {
        refused(guard("https://media.example:8920"), "https://media.example.:8920/Videos/x/stream")
        refused(guard("https://media.example.:8920"), "https://media.example:8920/Videos/x/stream")
    }

    @Test fun anExplicitDefaultPortIsTheSameAuthority() {
        passes(guard("http://media.example"), "http://media.example:80/Videos/x/stream")
        passes(guard("https://media.example"), "https://media.example:443/Videos/x/stream")
        passes(guard("http://media.example:80"), "http://media.example/Videos/x/stream")
        refused(guard("http://media.example"), "http://media.example:443/Videos/x/stream")
    }
}
