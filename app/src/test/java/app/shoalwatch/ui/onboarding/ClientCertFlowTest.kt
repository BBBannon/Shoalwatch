package app.shoalwatch.ui.onboarding

import app.shoalwatch.data.StubConnections
import app.shoalwatch.data.inMemoryDb
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.servers.AuthRepository
import app.shoalwatch.data.servers.AuthenticatorImpl
import app.shoalwatch.data.servers.CertificateProber
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.JellyfinGatewayImpl
import app.shoalwatch.data.servers.ServerClients
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.net.HttpClientFactory
import app.shoalwatch.net.spkiSha256Base64
import app.shoalwatch.security.SecretBox
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeChooser
import app.shoalwatch.ui.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

/**
 * Review I1 through the real add-server flow: AddServerViewModel -> AddressChecker -> AuthRepository ->
 * JellyfinGatewayImpl -> HttpClientFactory -> a MockWebServer that requires a client certificate. The error
 * kind comes from the real mapping, not from a fake. Order for a self-signed server that also needs a client
 * certificate: first "trust this certificate", then "choose a certificate".
 */
@RunWith(RobolectricTestRunner::class)
class ClientCertFlowTest {
    @get:Rule val main = MainDispatcherRule()

    private val serverId = "0123abcd456789ef0123456789abcdef"
    private val root = HeldCertificate.Builder().certificateAuthority(0).build()
    private val clientRoot = HeldCertificate.Builder().certificateAuthority(0).build()
    private val clientLeaf = HeldCertificate.Builder().commonName("phone").signedBy(clientRoot).build()
    private val web = MockWebServer()
    private lateinit var db: AppDatabase

    @After fun stop() {
        web.shutdown()
        if (::db.isInitialized) db.close()
    }

    private fun startServer(certificate: HeldCertificate, chain: List<HeldCertificate>) {
        web.useHttps(
            HandshakeCertificates.Builder()
                .heldCertificate(certificate, *chain.map { it.certificate }.toTypedArray())
                .addTrustedCertificate(clientRoot.certificate)
                .build().sslSocketFactory(),
            false,
        )
        web.requireClientAuth()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"Id":"$serverId","ServerName":"Den","Version":"10.10.3"}""")
        }
        web.start()
    }

    private fun viewModel(): Pair<AddServerViewModel, FakeCatalog> {
        val factory = HttpClientFactory(
            HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager,
            { alias -> require(alias == "phone-cert"); HandshakeCertificates.Builder().heldCertificate(clientLeaf).build().keyManager },
        )
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        db = inMemoryDb()
        val repo = AuthRepository(
            JellyfinGatewayImpl(ServerClients(factory)), ServerStore(db, SecretBox { key }),
            DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1"), StubConnections { null },
        )
        val catalog = FakeCatalog()
        return AddServerViewModel(AuthenticatorImpl(repo), CertificateProber(factory::probeCertificate), catalog) to catalog
    }

    private fun AddServerViewModel.awaitCheck(match: (Check) -> Boolean): Check =
        runBlocking { withTimeout(15_000) { state.first { match(it.check) }.check } }

    private fun AddServerViewModel.awaitStage(stage: AddServerStage) =
        runBlocking { withTimeout(15_000) { state.first { it.stage == stage } } }

    @Test fun aTrustedServerThatRequiresAClientCertificateAsksForOneThenConnectsWithIt() {
        startServer(HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").signedBy(root).build(), listOf(root))
        val (vm, catalog) = viewModel()
        vm.onAddressChange("https://127.0.0.1:${web.port}")
        vm.continueClicked()
        assertEquals(Check.NeedsClientCertificate, vm.awaitCheck { it !is Check.Checking && it !is Check.Idle })

        runBlocking { vm.chooseClientCertificate(FakeChooser(null)) } // cancelled in the system picker
        assertEquals(Check.NeedsClientCertificate, vm.state.value.check)

        runBlocking { vm.chooseClientCertificate(FakeChooser("phone-cert")) }
        vm.awaitStage(AddServerStage.Found)
        vm.confirmFound()
        vm.skipSecondAddress()
        runBlocking { withTimeout(5_000) { vm.state.first { it.savedServerId != null } } }
        assertEquals("phone-cert", catalog.upserts.single().clientCertAlias)
        assertEquals(null, catalog.upserts.single().pinnedSpkiSha256)
    }

    @Test fun aSelfSignedServerThatAlsoRequiresAClientCertificateAsksForTrustFirstThenTheCertificate() {
        val selfSigned = HeldCertificate.Builder().commonName("lan").addSubjectAlternativeName("127.0.0.1").build()
        startServer(selfSigned, emptyList())
        val (vm, catalog) = viewModel()
        vm.onAddressChange("https://127.0.0.1:${web.port}")
        vm.continueClicked()

        val trust = vm.awaitCheck { it is Check.NeedsCertificateTrust || it is Check.Failed || it is Check.NeedsClientCertificate }
        assertTrue("trust is asked first, got $trust", trust is Check.NeedsCertificateTrust)
        vm.trustCertificate()
        assertEquals(Check.NeedsClientCertificate, vm.awaitCheck { it is Check.NeedsClientCertificate || it is Check.Failed })

        runBlocking { vm.chooseClientCertificate(FakeChooser("phone-cert")) }
        vm.awaitStage(AddServerStage.Found)
        vm.confirmFound()
        vm.skipSecondAddress()
        runBlocking { withTimeout(5_000) { vm.state.first { it.savedServerId != null } } }
        val saved = catalog.upserts.single()
        assertEquals(spkiSha256Base64(selfSigned.certificate), saved.pinnedSpkiSha256)
        assertEquals("phone-cert", saved.clientCertAlias)
    }
}
