package app.shoalwatch.data

import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerClients
import app.shoalwatch.data.servers.ServerNet
import app.shoalwatch.data.servers.ServerNetDraft
import app.shoalwatch.net.HttpClientFactory
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.net.ServerNetConfig
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509KeyManager

private object NoKeys : X509KeyManager {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = null
    override fun getPrivateKey(alias: String?): PrivateKey? = null
}

class ServerClientsTest {
    private val clients = ServerClients(HttpClientFactory(HandshakeCertificates.Builder().build().trustManager, { _ -> NoKeys }))
    private val a = ServerAddress.parse("https://a.example")
    private val b = ServerAddress.parse("https://b.example")
    private val c = ServerAddress.parse("https://c.example")
    private val pin = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    private val device = DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1")

    /** [authKey] "s1:u1" style = a signed-in config for that account; null = anonymous. */
    private fun config(draft: ServerNetDraft = ServerNetDraft(), authKey: String? = null, address: ServerAddress = a): ServerNetConfig {
        if (authKey == null) return ServerNet.anonymous(listOf(address), draft, device)
        val (serverId, userId) = authKey.split(":")
        val server = Server(serverId, "Den", "10.10.3", address, null, draft.allowCleartext, draft.pinnedSpkiSha256, draft.clientCertAlias, draft.customHeaders)
        return ServerNet.authenticated(listOf(address), server, userId, device) { _, _ -> "tok-${System.nanoTime()}" }
    }

    @Test fun configEqualityIsByValueAndIgnoresTheProviderLambda() {
        assertEquals(config(), config())
        assertEquals(config().hashCode(), config().hashCode())
        assertNotEquals(config(), config(authKey = "s1:u1"))
        assertFalse(config().toString().contains("example"))
    }

    @Test fun aClientIsCurrentUntilItsServerIsEvicted() {
        val client = clients.clientFor(config(authKey = "s1:u1"))
        assertTrue(clients.isCurrent(client))
        clients.evict("s1", emptyList())
        assertFalse(clients.isCurrent(client))
        assertFalse("a rebuilt client is a new one", clients.isCurrent(client).also { clients.clientFor(config(authKey = "s1:u1")) })
        assertFalse(clients.isCurrent(client))
    }

    @Test fun sameSettingsGiveTheSameClientAcrossSeparateConfigs() {
        assertSame(clients.clientFor(config()), clients.clientFor(config()))
        assertSame(clients.clientFor(config(authKey = "s1:u1")), clients.clientFor(config(authKey = "s1:u1")))
    }

    @Test fun anyChangedSecuritySettingGivesADifferentClient() {
        val base = clients.clientFor(config())
        assertNotSame(base, clients.clientFor(config(ServerNetDraft(pinnedSpkiSha256 = pin))))
        assertNotSame(base, clients.clientFor(config(ServerNetDraft(allowCleartext = true))))
        assertNotSame(base, clients.clientFor(config(ServerNetDraft(customHeaders = mapOf("X-A" to "b")))))
        assertNotSame(base, clients.clientFor(config(ServerNetDraft(clientCertAlias = "c"))))
        assertNotSame(base, clients.clientFor(config(address = b)))
        assertNotSame(base, clients.clientFor(config(authKey = "s1:u1")))
    }

    @Test fun clientsDoNotFollowRedirects() = assertFalse(clients.clientFor(config()).followRedirects)

    @Test fun evictDropsClientsForTheServerByAccountKeyOrAddress() {
        val anon = clients.clientFor(config())
        val signedIn = clients.clientFor(config(authKey = "s1:u1", address = b))
        val unrelated = clients.clientFor(config(authKey = "s2:u9", address = c))

        clients.evict("s1", listOf(a))

        assertNotSame(anon, clients.clientFor(config()))
        assertNotSame(signedIn, clients.clientFor(config(authKey = "s1:u1", address = b)))
        assertSame(unrelated, clients.clientFor(config(authKey = "s2:u9", address = c)))
    }
}
