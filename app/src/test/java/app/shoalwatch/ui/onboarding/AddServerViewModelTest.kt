package app.shoalwatch.ui.onboarding

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.ServerInfo
import app.shoalwatch.data.servers.ServerNetDraft
import app.shoalwatch.net.fingerprintForDisplay
import app.shoalwatch.ui.FakeAuthenticator
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeChooser
import app.shoalwatch.ui.FakeProber
import app.shoalwatch.ui.AddressRole
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.PIN
import app.shoalwatch.ui.address
import app.shoalwatch.ui.certificate
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
import java.io.IOException

class AddServerViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val auth = FakeAuthenticator()
    private val prober = FakeProber()
    private val catalog = FakeCatalog()
    private fun vm() = AddServerViewModel(auth, prober, catalog)

    private val blockHttp: suspend (String, ServerNetDraft) -> ServerInfo = { text, draft ->
        if (!draft.allowCleartext) throw AppError.of(AppError.Kind.CLEARTEXT_BLOCKED)
        serverInfo(text)
    }
    private val blockUntrusted: suspend (String, ServerNetDraft) -> ServerInfo = { text, draft ->
        if (draft.pinnedSpkiSha256 == null) throw AppError.of(AppError.Kind.TLS_UNTRUSTED)
        serverInfo(if ("://" in text) text else "https://$text")
    }

    private fun AddServerViewModel.type(text: String) { onAddressChange(text); continueClicked() }

    // --- plain http ---------------------------------------------------------------------------------------------

    @Test fun plainHttpStopsAtAWarningAndCancellingSavesNothing() {
        auth.onFind = blockHttp
        val vm = vm()
        vm.type("http://192.168.1.10:8096")
        assertEquals(Check.NeedsHttpConsent, vm.state.value.check)
        assertNull(vm.state.value.found)
        vm.cancelCheck()
        assertEquals(Check.Idle, vm.state.value.check)
        assertTrue(catalog.upserts.isEmpty())
    }

    @Test fun allowingHttpRechecksWithCleartextAndSavesOnlyAfterTheUserFinishes() {
        auth.onFind = blockHttp
        val vm = vm()
        vm.type("http://192.168.1.10:8096")
        assertTrue(catalog.upserts.isEmpty())
        vm.allowHttp()
        assertTrue(auth.findCalls.last().second.allowCleartext)
        assertEquals(AddServerStage.Found, vm.state.value.stage)
        assertEquals("Den", vm.state.value.found?.name)
        assertTrue("nothing is saved at the found step", catalog.upserts.isEmpty())
        vm.confirmFound()
        assertEquals(AddServerStage.SecondAddress, vm.state.value.stage)
        assertTrue("nothing is saved before the second-address step ends", catalog.upserts.isEmpty())
        vm.skipSecondAddress()
        val saved = catalog.upserts.single()
        assertTrue(saved.allowCleartext)
        assertEquals(address("http://192.168.1.10:8096"), saved.homeAddress)
        assertNull(saved.awayAddress)
        assertEquals("srv-1", vm.state.value.savedServerId)
    }

    @Test fun withoutASchemeHttpsIsTriedFirstAndHttpIsOnlyOfferedBehindItsWarning() {
        auth.onFind = { _, _ -> throw AppError.of(AppError.Kind.NETWORK) }
        val vm = vm()
        vm.type("192.168.1.10:8096")
        assertEquals(1, auth.findCalls.size)
        assertFalse("first attempt must not allow cleartext", auth.findCalls.single().second.allowCleartext)
        val failed = vm.state.value.check as Check.Failed
        assertTrue(failed.offerHttp)
        assertEquals(AppError.of(AppError.Kind.NETWORK).userMessage, failed.message)
        vm.offerHttp()
        assertEquals(Check.NeedsHttpConsent, vm.state.value.check)
        assertEquals("no request until the user agrees", 1, auth.findCalls.size)
        auth.onFind = { text, _ -> serverInfo("http://$text") }
        vm.allowHttp()
        assertEquals(2, auth.findCalls.size)
        assertTrue(auth.findCalls.last().second.allowCleartext)
        assertEquals(AddServerStage.Found, vm.state.value.stage)
    }

    @Test fun httpIsNotOfferedWhenTheUserTypedAnExplicitHttpsScheme() {
        auth.onFind = { _, _ -> throw AppError.of(AppError.Kind.NETWORK) }
        val vm = vm()
        vm.type("https://jf.example.org")
        assertFalse((vm.state.value.check as Check.Failed).offerHttp)
        vm.offerHttp()
        assertTrue(vm.state.value.check is Check.Failed)
    }

    // --- untrusted certificate ----------------------------------------------------------------------------------

    @Test fun anUntrustedCertificateShowsItsFingerprintAndSavesNothingUntilTrusted() {
        auth.onFind = blockUntrusted
        val vm = vm()
        vm.type("den.example")
        val prompt = vm.state.value.check as Check.NeedsCertificateTrust
        assertEquals(certificate().fingerprintForDisplay(), prompt.certificate.fingerprint)
        assertEquals("CN=den.example", prompt.certificate.subject)
        assertEquals(address("https://den.example"), prober.probed.single())
        assertTrue(catalog.upserts.isEmpty())
        vm.cancelCheck()
        assertTrue(catalog.upserts.isEmpty())
        assertEquals(Check.Idle, vm.state.value.check)
    }

    @Test fun trustingPinsTheProbedFingerprintAndRechecks() {
        auth.onFind = blockUntrusted
        val vm = vm()
        vm.type("den.example")
        vm.trustCertificate()
        assertEquals(PIN, auth.findCalls.last().second.pinnedSpkiSha256)
        vm.confirmFound()
        vm.skipSecondAddress()
        assertEquals(PIN, catalog.upserts.single().pinnedSpkiSha256)
    }

    @Test fun aServerThatStillFailsAfterTrustDoesNotPromptAgain() {
        auth.onFind = { _, _ -> throw AppError.of(AppError.Kind.TLS_UNTRUSTED) }
        val vm = vm()
        vm.type("den.example")
        vm.trustCertificate()
        assertEquals(AppError.of(AppError.Kind.TLS_UNTRUSTED).userMessage, (vm.state.value.check as Check.Failed).message)
        assertEquals(1, prober.probed.size)
    }

    // --- client certificate -------------------------------------------------------------------------------------

    // The client-certificate flow is tested through the real error mapping in ClientCertFlowTest.

    // --- version ------------------------------------------------------------------------------------------------

    @Test fun anUnsupportedVersionIsAWarningTheUserMustConfirmBeforeAnythingIsSaved() {
        auth.onFind = { text, _ -> serverInfo("https://$text", version = "10.8.0") }
        val vm = vm()
        vm.type("den.example")
        assertEquals(ServerInfo.Compatibility.TOO_OLD, vm.state.value.found?.compatibility)
        assertEquals(AddServerStage.Found, vm.state.value.stage)
        assertTrue(catalog.upserts.isEmpty())
        vm.confirmFound()
        vm.skipSecondAddress()
        assertEquals(1, catalog.upserts.size)
    }

    // --- second address -----------------------------------------------------------------------------------------

    private fun foundAt(vm: AddServerViewModel, typed: String) {
        vm.type(typed)
        vm.confirmFound()
        assertEquals(AddServerStage.SecondAddress, vm.state.value.stage)
    }

    @Test fun aSecondAddressForADifferentServerIsRejectedAndNotSaved() {
        val vm = vm()
        foundAt(vm, "https://192.168.1.10:8920")
        auth.onFind = { text, _ -> serverInfo(if ("://" in text) text else "https://$text", id = "someone-else") }
        vm.onSecondAddressChange("https://jf.example.org")
        vm.addSecondAddress()
        assertEquals(AppError.of(AppError.Kind.SERVER_MISMATCH).userMessage, (vm.state.value.check as Check.Failed).message)
        assertTrue(catalog.upserts.isEmpty())
        vm.skipSecondAddress()
        assertNull(catalog.upserts.single().awayAddress)
    }

    @Test fun aVerifiedSecondAddressTakesTheOtherSlot() {
        val vm = vm()
        foundAt(vm, "https://192.168.1.10:8920")
        vm.onSecondAddressChange("jf.example.org")
        vm.addSecondAddress()
        val saved = catalog.upserts.single()
        assertEquals(address("https://192.168.1.10:8920"), saved.homeAddress)
        assertEquals(address("https://jf.example.org"), saved.awayAddress)
        assertEquals("srv-1", vm.state.value.savedServerId)
    }

    @Test fun anAwayAddressTypedFirstGetsTheHomeAddressSecond() {
        val vm = vm()
        foundAt(vm, "jf.example.org")
        assertFalse(vm.state.value.found!!.firstIsHome)
        vm.onSecondAddressChange("192.168.1.10:8096")
        vm.addSecondAddress()
        val saved = catalog.upserts.single()
        assertEquals(address("https://jf.example.org"), saved.awayAddress)
        assertNotNull(saved.homeAddress)
    }

    @Test fun aSecondAddressNeedingHttpGetsTheSameBlockingWarning() {
        val vm = vm()
        foundAt(vm, "https://jf.example.org")
        auth.onFind = blockHttp
        vm.onSecondAddressChange("http://192.168.1.10:8096")
        vm.addSecondAddress()
        assertEquals(Check.NeedsHttpConsent, vm.state.value.check)
        vm.cancelCheck()
        assertTrue(catalog.upserts.isEmpty())
        vm.addSecondAddress()
        vm.allowHttp()
        val saved = catalog.upserts.single()
        assertTrue(saved.allowCleartext)
        assertEquals(address("http://192.168.1.10:8096"), saved.homeAddress)
    }

    @Test fun aSecondAddressNeverInheritsTheFirstAddressConsentToHttp() {
        auth.onFind = blockHttp
        val vm = vm()
        vm.type("http://192.168.1.10:8096")
        vm.allowHttp()
        vm.confirmFound()
        vm.onSecondAddressChange("http://nas.example:8096")
        vm.addSecondAddress()
        assertEquals("the warning appears again for this address", Check.NeedsHttpConsent, vm.state.value.check)
        assertFalse(auth.findCalls.last().second.allowCleartext)
        assertTrue("nothing saved until confirmed", catalog.upserts.isEmpty())
        vm.cancelCheck()
        vm.skipSecondAddress()
        val saved = catalog.upserts.single()
        assertTrue("the first address own consent is kept", saved.allowCleartext)
        assertNull("the refused address is not saved", saved.awayAddress)
    }

    @Test fun confirmingTheSecondWarningSavesBothAddresses() {
        auth.onFind = blockHttp
        val vm = vm()
        vm.type("http://192.168.1.10:8096")
        vm.allowHttp()
        vm.confirmFound()
        vm.onSecondAddressChange("http://nas.example:8096")
        vm.addSecondAddress()
        vm.allowHttp()
        assertEquals(address("http://nas.example:8096"), catalog.upserts.single().awayAddress)
    }

    @Test fun theHttpWarningNamesWhichAddressItIsFor() {
        auth.onFind = blockHttp
        val vm = vm()
        vm.onAddressChange("http://192.168.1.10:8096")
        vm.continueClicked()
        assertEquals(AddressRole.Home, vm.state.value.addressRole)
        vm.allowHttp()
        vm.confirmFound()
        vm.onSecondAddressChange("http://away.example:8096")
        vm.addSecondAddress()
        assertEquals(AddressRole.Away, vm.state.value.addressRole)
    }

    @Test fun cancellingAWarningOnTheSecondAddressDoesNotLeaveItsConsentBehind() {
        val vm = vm()
        foundAt(vm, "https://jf.example.org")
        auth.onFind = blockHttp
        vm.onSecondAddressChange("http://192.168.1.10:8096")
        vm.addSecondAddress()
        vm.cancelCheck()
        vm.skipSecondAddress()
        assertFalse(catalog.upserts.single().allowCleartext)
    }

    // --- errors, saving, privacy --------------------------------------------------------------------------------

    @Test fun errorsAreExactlyTheAppErrorUserMessage() {
        val vm = vm()
        auth.onFind = { _, _ -> throw AppError.of(AppError.Kind.SERVER_NOT_FOUND) }
        vm.type("nope.example")
        assertEquals(AppError.of(AppError.Kind.SERVER_NOT_FOUND).userMessage, (vm.state.value.check as Check.Failed).message)
        auth.onFind = { _, _ -> throw IOException("connect to 10.1.2.3 failed token=abc") }
        vm.continueClicked()
        val message = (vm.state.value.check as Check.Failed).message
        assertEquals(AppError.from(IOException("x")).userMessage, message)
        assertFalse("10.1.2.3" in message)
    }

    @Test fun aSaveFailureIsShownAsAnAppErrorMessageAndDoesNotMoveOn() {
        catalog.upsertFailure = IllegalStateException("db is on fire at 10.1.2.3")
        val vm = vm()
        foundAt(vm, "den.example")
        vm.skipSecondAddress()
        assertEquals(AppError.from(IllegalStateException()).userMessage, vm.state.value.error)
        assertNull(vm.state.value.savedServerId)
        assertFalse(vm.state.value.saving)
    }

    @Test fun anAlreadySavedServerIsToldSoAndSettingsAreNeverChanged() {
        catalog.serverList.value = listOf(testServer(id = "srv-1", home = null, away = "https://old.example"))
        val vm = vm()
        vm.type("den.example")
        assertTrue(vm.state.value.found!!.alreadySaved)
        vm.confirmFound()
        assertTrue("no second-address step that could be silently discarded", catalog.upserts.isEmpty())
        assertEquals("srv-1", vm.state.value.savedServerId)
    }

    @Test fun aBlankAddressIsIgnored() {
        val vm = vm()
        vm.type("   ")
        assertTrue(auth.findCalls.isEmpty())
    }

    @Test fun backReturnsToTheAddressStepWithoutSaving() {
        val vm = vm()
        foundAt(vm, "den.example")
        vm.backToAddress()
        assertEquals(AddServerStage.Address, vm.state.value.stage)
        assertNull(vm.state.value.found)
        assertTrue(catalog.upserts.isEmpty())
    }

    @Test fun theStateNeverPrintsAnAddress() {
        val vm = vm()
        vm.type("secret-host.example")
        assertFalse("secret-host" in vm.state.value.toString())
    }
}
