package app.offlinefin.net

import android.content.Context
import android.security.KeyChain
import android.security.KeyChainException
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.X509KeyManager

/**
 * Presents the client certificate stored in Android's `KeyChain` under [alias], and no other.
 *
 * `KeyChain.getPrivateKey` and `getCertificateChain` block and must never run on the main thread.
 * OkHttp calls key managers from its own connection threads during the TLS handshake, so this is
 * safe as long as it is only used through the OkHttp client. Failures return null, which makes the
 * handshake proceed without a client certificate.
 */
class KeyChainKeyManager(context: Context, private val alias: String) : X509KeyManager {
    private val appContext = context.applicationContext

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(alias)

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
        if (alias != this.alias) return null
        return try {
            KeyChain.getCertificateChain(appContext, alias)
        } catch (_: KeyChainException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    override fun getPrivateKey(alias: String?): PrivateKey? {
        if (alias != this.alias) return null
        return try {
            KeyChain.getPrivateKey(appContext, alias)
        } catch (_: KeyChainException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }
}
