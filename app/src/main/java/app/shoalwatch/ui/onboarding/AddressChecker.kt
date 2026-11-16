package app.shoalwatch.ui.onboarding

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Authenticator
import app.shoalwatch.data.servers.CertificateProber
import app.shoalwatch.data.servers.ServerInfo
import app.shoalwatch.data.servers.ServerNetDraft
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.ui.CertificateSummary
import app.shoalwatch.ui.toSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where one address check has got to. Every security warning is a state that waits for an explicit choice. */
sealed interface Check {
    data object Idle : Check
    data object Checking : Check

    /** The address is plain `http`: blocked until the user allows it for this server. */
    data object NeedsHttpConsent : Check

    /** Reading the certificate the server presents. */
    data object LoadingCertificate : Check

    /** The server uses a certificate the system does not trust: show it and ask. */
    data class NeedsCertificateTrust(val certificate: CertificateSummary) : Check

    /** The server asks for a certificate from this phone. */
    data object NeedsClientCertificate : Check

    /** [offerHttp]: https failed to connect and no scheme was typed, so plain http can be offered (behind its warning). */
    data class Failed(val message: String, val offerHttp: Boolean = false) : Check

    /**
     * The server answered; [draft] holds the security choices made on the way (cleartext, pin, client certificate).
     * Handed to the checker's `onVerified` callback, never published as [AddressChecker.state].
     */
    data class Verified(val info: ServerInfo, val draft: ServerNetDraft) : Check
}

/**
 * Checks that an address is a Jellyfin server, one explicit step at a time: https is tried first, then
 * each problem (plain http, untrusted certificate, client certificate) stops with a [Check] state that only
 * a user action moves on, and the address is re-checked with the new setting. Used for the first address
 * and for every later edit. With `expectedServerId` the answering server must be that server
 * (`SERVER_MISMATCH` otherwise).
 *
 * Nothing is saved here: [onVerified] decides what to do with the result (the state is back to
 * [Check.Idle] when it is called). Never logs.
 */
class AddressChecker(
    private val auth: Authenticator,
    private val prober: CertificateProber,
    private val scope: CoroutineScope,
    private val onVerified: suspend (Check.Verified) -> Unit,
) {
    private val mutableState = MutableStateFlow<Check>(Check.Idle)
    val state: StateFlow<Check> = mutableState

    private var job: Job? = null
    private var input = ""
    private var draft = ServerNetDraft()
    private var expectedServerId: String? = null

    fun start(input: String, draft: ServerNetDraft, expectedServerId: String? = null) {
        this.input = input.trim()
        this.draft = draft
        this.expectedServerId = expectedServerId
        run()
    }

    /** From [Check.Failed.offerHttp]: show the plain-http warning. */
    fun offerHttp() {
        if ((mutableState.value as? Check.Failed)?.offerHttp == true) mutableState.value = Check.NeedsHttpConsent
    }

    /** The user chose "Use anyway": allow plain http for this server and check again. */
    fun allowHttp() {
        if (mutableState.value !is Check.NeedsHttpConsent) return
        draft = draft.copy(allowCleartext = true)
        run()
    }

    /** The user chose "Trust this certificate": pin it and check again. */
    fun trustCertificate() {
        val shown = (mutableState.value as? Check.NeedsCertificateTrust)?.certificate ?: return
        draft = draft.copy(pinnedSpkiSha256 = shown.spkiSha256)
        run()
    }

    fun useClientCertificate(alias: String) {
        if (mutableState.value !is Check.NeedsClientCertificate) return
        draft = draft.copy(clientCertAlias = alias)
        run()
    }

    /** Stops any check in progress and forgets every choice made so far. */
    fun cancel() {
        job?.cancel()
        mutableState.value = Check.Idle
    }

    private fun run() {
        job?.cancel()
        mutableState.value = Check.Checking
        job = scope.launch {
            val result = attempt()
            if (result is Check.Verified) {
                mutableState.value = Check.Idle
                onVerified(result)
            } else {
                mutableState.value = result
            }
        }
    }

    private suspend fun attempt(): Check = try {
        val info = auth.findServer(input, draft)
        val expected = expectedServerId
        if (expected != null && info.id != expected) {
            Check.Failed(AppError.of(AppError.Kind.SERVER_MISMATCH).userMessage)
        } else {
            Check.Verified(info, draft)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failure(AppError.from(e))
    }

    private suspend fun failure(error: AppError): Check = when (error.kind) {
        AppError.Kind.CLEARTEXT_BLOCKED ->
            if (draft.allowCleartext) Check.Failed(error.userMessage) else Check.NeedsHttpConsent
        AppError.Kind.TLS_UNTRUSTED -> untrusted(error)
        AppError.Kind.CLIENT_CERT_REQUIRED ->
            if (draft.clientCertAlias != null) Check.Failed(error.userMessage) else Check.NeedsClientCertificate
        AppError.Kind.NETWORK, AppError.Kind.TIMEOUT ->
            Check.Failed(error.userMessage, offerHttp = "://" !in input && !draft.allowCleartext)
        else -> Check.Failed(error.userMessage)
    }

    /** Reads the certificate for the trust prompt. A server that is already pinned never gets a second prompt here. */
    private suspend fun untrusted(error: AppError): Check {
        if (draft.pinnedSpkiSha256 != null) return Check.Failed(error.userMessage)
        mutableState.value = Check.LoadingCertificate
        return try {
            val certificate = prober.probe(ServerAddress.parse(input))
            if (certificate == null) Check.Failed(error.userMessage) else Check.NeedsCertificateTrust(certificate.toSummary())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Check.Failed(AppError.from(e).userMessage)
        }
    }
}
