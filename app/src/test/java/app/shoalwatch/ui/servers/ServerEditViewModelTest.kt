package app.shoalwatch.ui.servers

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.net.fingerprintForDisplay
import app.shoalwatch.ui.FakeAuthenticator
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeChooser
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.FakeProber
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.PIN
import app.shoalwatch.ui.address
import app.shoalwatch.ui.certificate
import app.shoalwatch.ui.onboarding.Check
import app.shoalwatch.ui.serverInfo
import app.shoalwatch.ui.testServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ServerEditViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val auth = FakeAuthenticator()
    private val prober = FakeProber()
    private val connections = FakeConnections()

    private fun setup(server: app.shoalwatch.data.servers.Server = testServer(home = "https://192.168.1.10:8920", cleartext = false)): Pair<FakeCatalog, ServerEditViewModel> {
        val catalog = FakeCatalog(servers = listOf(server))
        return catalog to ServerEditViewModel(server.id, catalog, auth, prober, connections)
    }

    private val secret = "proxy-token-9f8e7d"

    // --- showing the server -------------------------------------------------------------------------------------

    @Test fun showsTheSettingsAndNeverPrintsThem() {
        val (_, vm) = setup(testServer(headers = mapOf("X-Proxy" to secret), pin = PIN, alias = "cert"))
        val s = vm.state.value
        assertTrue(s.loaded)
        assertEquals(app.shoalwatch.net.fingerprintForDisplay(PIN), s.pinFingerprint)
        assertTrue(s.hasClientCertificate)
        assertEquals(listOf(HeaderItem("X-Proxy", secret)), s.headers)
        assertFalse(secret in s.toString())
        assertFalse(secret in s.headers.toString())
    }

    @Test fun aRemovedServerIsReported() {
        val (catalog, vm) = setup()
        catalog.serverList.value = emptyList()
        assertTrue(vm.state.value.missing)
    }

    @Test fun retryRefreshesTheConnection() {
        val (_, vm) = setup()
        vm.retry()
        assertEquals(1, connections.refreshes)
    }

    // --- addresses ----------------------------------------------------------------------------------------------

    @Test fun aNewAddressIsCheckedAsTheSameServerBeforeItIsSaved() {
        val (catalog, vm) = setup()
        vm.editAddress(AddressSlot.Away)
        vm.onAddressText("jf.example.org")
        vm.saveAddress()
        assertEquals("srv-1", catalog.upserts.single().id)
        assertEquals(address("https://jf.example.org"), catalog.upserts.single().awayAddress)
        assertEquals(address("https://192.168.1.10:8920"), catalog.upserts.single().homeAddress)
        assertNull(vm.state.value.pane)
    }

    @Test fun anAddressForADifferentServerIsRejectedAndNothingIsSaved() {
        val (catalog, vm) = setup()
        auth.onFind = { text, _ -> serverInfo(text, id = "someone-else") }
        vm.editAddress(AddressSlot.Away)
        vm.onAddressText("https://other.example")
        vm.saveAddress()
        assertEquals(AppError.of(AppError.Kind.SERVER_MISMATCH).userMessage, (vm.state.value.addressCheck as Check.Failed).message)
        assertTrue(catalog.upserts.isEmpty())
    }

    @Test fun anAddressNeedingPlainHttpGetsTheBlockingWarningAndOnlyThenIsSavedWithCleartext() {
        val (catalog, vm) = setup()
        auth.onFind = { text, d ->
            if (!d.allowCleartext) throw AppError.of(AppError.Kind.CLEARTEXT_BLOCKED)
            serverInfo(text)
        }
        vm.editAddress(AddressSlot.Away)
        vm.onAddressText("http://10.9.9.9:8096")
        vm.saveAddress()
        assertEquals(Check.NeedsHttpConsent, vm.state.value.addressCheck)
        assertTrue(catalog.upserts.isEmpty())
        vm.allowHttpForAddress()
        assertTrue(catalog.upserts.single().allowCleartext)
    }

    @Test fun anEditedAddressStartsWithoutTheServerConsentToHttp() {
        val (catalog, vm) = setup(testServer(home = "http://192.168.1.10:8096", away = null, cleartext = true))
        auth.onFind = { text, d ->
            if (!d.allowCleartext && text.startsWith("http://")) throw AppError.of(AppError.Kind.CLEARTEXT_BLOCKED)
            serverInfo(text)
        }
        vm.editAddress(AddressSlot.Away)
        vm.onAddressText("http://10.9.9.9:8096")
        vm.saveAddress()
        assertEquals(Check.NeedsHttpConsent, vm.state.value.addressCheck)
        assertEquals(app.shoalwatch.ui.AddressRole.Away, vm.state.value.addressRole)
        assertTrue(catalog.upserts.isEmpty())
        vm.cancelAddressCheck()
        assertTrue("server unchanged", catalog.upserts.isEmpty())
        vm.saveAddress()
        vm.allowHttpForAddress()
        assertEquals(address("http://10.9.9.9:8096"), catalog.upserts.single().awayAddress)
        assertTrue(catalog.upserts.single().allowCleartext)
    }

    @Test fun replacingTheOnlyHttpAddressWithHttpsClearsTheCleartextFlag() {
        val (catalog, vm) = setup(testServer(home = "http://192.168.1.10:8096", away = null, cleartext = true))
        vm.editAddress(AddressSlot.Home)
        vm.onAddressText("jf.example.org")
        vm.saveAddress()
        assertEquals(address("https://jf.example.org"), catalog.upserts.single().homeAddress)
        assertFalse("no address uses http any more", catalog.upserts.single().allowCleartext)
    }

    @Test fun removingTheOnlyHttpAddressClearsTheCleartextFlag() {
        val (catalog, vm) = setup(testServer(home = "http://192.168.1.10:8096", away = "https://away.example", cleartext = true))
        vm.editAddress(AddressSlot.Home)
        vm.onAddressText("")
        vm.saveAddress()
        assertFalse(catalog.upserts.single().allowCleartext)
    }

    @Test fun anEditThatNeedsNoConsentKeepsTheServerExistingFlag() {
        val (catalog, vm) = setup(testServer(home = "http://192.168.1.10:8096", away = null, cleartext = true))
        vm.editAddress(AddressSlot.Away)
        vm.onAddressText("jf.example.org")
        vm.saveAddress()
        assertTrue(catalog.upserts.single().allowCleartext)
    }

    @Test fun aForgottenCertificateIsNotResurrectedByAnAddressCheckThatStartedBefore() {
        val (catalog, vm) = setup(testServer(home = "https://192.168.1.10:8920", away = null, cleartext = false, pin = PIN))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        auth.onFind = { text, _ -> gate.await(); serverInfo(if ("://" in text) text else "https://$text") }
        vm.editAddress(AddressSlot.Away)
        vm.onAddressText("jf.example.org")
        vm.saveAddress()
        // The certificate is forgotten elsewhere while the check is still running.
        catalog.serverList.value = catalog.serverList.value.map { it.copy(pinnedSpkiSha256 = null) }
        gate.complete(Unit)
        assertEquals(address("https://jf.example.org"), catalog.upserts.single().awayAddress)
        assertNull("the forgotten pin stays forgotten", catalog.upserts.single().pinnedSpkiSha256)
    }

    @Test fun cancellingTheAddressEditSavesNothing() {
        val (catalog, vm) = setup()
        auth.onFind = { _, _ -> throw AppError.of(AppError.Kind.CLEARTEXT_BLOCKED) }
        vm.editAddress(AddressSlot.Away)
        vm.onAddressText("http://10.9.9.9:8096")
        vm.saveAddress()
        vm.dismissPane()
        assertNull(vm.state.value.pane)
        assertEquals(Check.Idle, vm.state.value.addressCheck)
        assertTrue(catalog.upserts.isEmpty())
    }

    @Test fun cancellingJustTheCheckKeepsTheTypedAddress() {
        val (catalog, vm) = setup()
        auth.onFind = { _, _ -> throw AppError.of(AppError.Kind.CLEARTEXT_BLOCKED) }
        vm.editAddress(AddressSlot.Away)
        vm.onAddressText("http://10.9.9.9:8096")
        vm.saveAddress()
        vm.cancelAddressCheck()
        assertEquals(Check.Idle, vm.state.value.addressCheck)
        assertEquals(EditPane.Address(AddressSlot.Away, "http://10.9.9.9:8096"), vm.state.value.pane)
        assertTrue(catalog.upserts.isEmpty())
    }

    @Test fun anAddressCanBeRemovedWhileAnotherRemains() {
        val (catalog, vm) = setup(testServer(home = "https://192.168.1.10:8920", away = "https://away.example", cleartext = false))
        vm.editAddress(AddressSlot.Away)
        vm.onAddressText("")
        vm.saveAddress()
        assertNull(catalog.upserts.single().awayAddress)
        assertTrue(auth.findCalls.isEmpty())
    }

    @Test fun theLastAddressCannotBeRemoved() {
        val (catalog, vm) = setup(testServer(home = "https://192.168.1.10:8920", away = null, cleartext = false))
        vm.editAddress(AddressSlot.Home)
        vm.onAddressText("")
        vm.saveAddress()
        assertTrue(catalog.upserts.isEmpty())
        assertTrue(vm.state.value.addressRequired)
        assertNull(vm.state.value.error)
    }

    // --- unencrypted http toggle --------------------------------------------------------------------------------

    @Test fun turningHttpOnShowsTheWarningAndOnlyConfirmingSavesIt() {
        val (catalog, vm) = setup()
        vm.setCleartext(true)
        assertEquals(EditPane.HttpWarning, vm.state.value.pane)
        assertTrue(catalog.upserts.isEmpty())
        vm.dismissPane()
        assertTrue(catalog.upserts.isEmpty())
        vm.setCleartext(true)
        vm.confirmCleartext()
        assertTrue(catalog.upserts.single().allowCleartext)
        assertNull(vm.state.value.pane)
    }

    @Test fun turningHttpOffIsImmediate() {
        val (catalog, vm) = setup(testServer(cleartext = true))
        vm.setCleartext(false)
        assertFalse(catalog.upserts.single().allowCleartext)
        assertNull(vm.state.value.pane)
    }

    // --- certificates -------------------------------------------------------------------------------------------

    @Test fun forgettingTheCertificateNeedsConfirmationAndThenRemovesThePin() {
        val (catalog, vm) = setup(testServer(pin = PIN))
        vm.askForgetCertificate()
        assertEquals(EditPane.ConfirmDestructive(Destructive.ForgetCertificate), vm.state.value.pane)
        assertTrue("nothing happens before confirming", catalog.upserts.isEmpty())
        vm.dismissPane()
        assertTrue(catalog.upserts.isEmpty())
        vm.askForgetCertificate()
        vm.confirmDestructive()
        assertNull(catalog.upserts.single().pinnedSpkiSha256)
        assertNull(vm.state.value.pane)
    }

    @Test fun aChangedCertificateRaisesTheProminentAlertFromTheConnectionOrADamagedPin() {
        val (_, vm) = setup()
        assertFalse(vm.state.value.certificateChanged)
        connections.stateFor("srv-1").value = Connection.Offline(AppError.Kind.TLS_PIN_CHANGED)
        assertTrue(vm.state.value.certificateChanged)
        assertTrue(vm.state.value.certificateNeedsReview)
        val (_, damaged) = setup(testServer(id = "srv-1", pinInvalid = true))
        assertTrue(damaged.state.value.certificateChanged)
    }

    @Test fun reviewingProbesShowsTheFingerprintAndNeverTrustsByItself() {
        val (catalog, vm) = setup(testServer(home = "https://192.168.1.10:8920", pin = PIN))
        connections.stateFor("srv-1").value = Connection.Offline(AppError.Kind.TLS_PIN_CHANGED)
        vm.reviewCertificate()
        val pane = vm.state.value.pane as EditPane.CertificateReview
        assertEquals(certificate().fingerprintForDisplay(), pane.certificate?.fingerprint)
        assertEquals(address("https://192.168.1.10:8920"), prober.probed.single())
        assertTrue("never auto-trusted", catalog.upserts.isEmpty())
        vm.trustReviewedCertificate()
        val saved = catalog.upserts.single()
        assertEquals(PIN, saved.pinnedSpkiSha256)
        assertFalse(saved.pinInvalid)
        assertNull(vm.state.value.pane)
    }

    @Test fun dismissingTheReviewTrustsNothing() {
        val (catalog, vm) = setup()
        vm.reviewCertificate()
        vm.dismissPane()
        assertTrue(catalog.upserts.isEmpty())
    }

    @Test fun aProbeFailureIsShownAsAnAppErrorMessage() {
        val (_, vm) = setup()
        prober.result = null // plain http or no certificate
        vm.reviewCertificate()
        val pane = vm.state.value.pane as EditPane.CertificateReview
        assertNull(pane.certificate)
        assertEquals(AppError.of(AppError.Kind.TLS_UNTRUSTED).userMessage, pane.error)
    }

    @Test fun aClientCertificateCanBeChosenAndRemovedAfterConfirming() = runBlocking {
        val (catalog, vm) = setup()
        vm.chooseClientCertificate(FakeChooser(null))
        assertTrue(catalog.upserts.isEmpty())
        vm.chooseClientCertificate(FakeChooser("my-cert"))
        assertEquals("my-cert", catalog.upserts.last().clientCertAlias)
        vm.askRemoveClientCertificate()
        assertEquals("my-cert", catalog.upserts.last().clientCertAlias)
        vm.confirmDestructive()
        assertNull(catalog.upserts.last().clientCertAlias)
    }

    // --- custom headers -----------------------------------------------------------------------------------------

    private fun ServerEditViewModel.saveHeader(name: String, value: String) {
        addHeader(); onHeaderName(name); onHeaderValue(value); saveHeader()
    }

    @Test fun aValidHeaderIsSaved() {
        val (catalog, vm) = setup()
        vm.saveHeader("X-Proxy-Key", secret)
        assertEquals(mapOf("X-Proxy-Key" to secret), catalog.upserts.single().customHeaders)
        assertNull(vm.state.value.pane)
    }

    @Test fun anInvalidNameShowsTheValidationMessageAndSavesNothing() {
        val (catalog, vm) = setup()
        vm.saveHeader("Bad Name", secret)
        val pane = vm.state.value.pane as EditPane.Header
        assertEquals("Custom header name contains an invalid character.", pane.error)
        assertTrue(catalog.upserts.isEmpty())
    }

    @Test fun aForbiddenNameIsRefused() {
        val (_, vm) = setup()
        vm.saveHeader("Authorization", secret)
        assertEquals("Custom header 'Authorization' is not allowed.", (vm.state.value.pane as EditPane.Header).error)
    }

    @Test fun anInvalidValueNeverEchoesTheValue() {
        val (catalog, vm) = setup()
        val bad = "line1\nline2-$secret"
        vm.saveHeader("X-Proxy", bad)
        val error = (vm.state.value.pane as EditPane.Header).error
        assertEquals("Custom header 'X-Proxy' has an invalid value.", error)
        assertFalse(secret in error.orEmpty())
        assertTrue(catalog.upserts.isEmpty())
    }

    @Test fun editingRenamesAHeaderWithoutLeavingTheOldOne() {
        val (catalog, vm) = setup(testServer(headers = mapOf("X-Old" to "v1")))
        vm.editHeader("X-Old")
        assertEquals("v1", (vm.state.value.pane as EditPane.Header).value)
        vm.onHeaderName("X-New")
        vm.saveHeader()
        assertEquals(mapOf("X-New" to "v1"), catalog.upserts.single().customHeaders)
    }

    @Test fun aHeaderWithTheSameNameInAnotherCaseReplacesTheOldOne() {
        val (catalog, vm) = setup(testServer(headers = mapOf("X-Proxy" to "old")))
        vm.saveHeader("x-proxy", "new")
        assertEquals(mapOf("x-proxy" to "new"), catalog.upserts.single().customHeaders)
    }

    @Test fun deletingAHeaderNeedsConfirmation() {
        val (catalog, vm) = setup(testServer(headers = mapOf("X-A" to "1", "X-B" to "2")))
        vm.askRemoveHeader("X-A")
        assertEquals(EditPane.ConfirmDestructive(Destructive.RemoveHeader("X-A")), vm.state.value.pane)
        assertTrue(catalog.upserts.isEmpty())
        vm.confirmDestructive()
        assertEquals(mapOf("X-B" to "2"), catalog.upserts.single().customHeaders)
    }

    @Test fun aSaveFailureIsAnAppErrorMessage() {
        val (catalog, vm) = setup()
        catalog.upsertFailure = IllegalStateException("db error for $secret")
        vm.setCleartext(false)
        assertEquals(AppError.from(IllegalStateException()).userMessage, vm.state.value.error)
        assertFalse(secret in vm.state.value.error.orEmpty())
    }
}
