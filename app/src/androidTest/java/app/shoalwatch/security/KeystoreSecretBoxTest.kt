package app.shoalwatch.security

import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeystoreSecretBoxTest {
    private val alias = "shoalwatch.secrets.test"

    @After fun cleanUp() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test fun roundTripWithRealKeystoreKey() {
        val box = SecretBox(KeystoreKeyProvider(alias))
        assertEquals("access-token", box.open(box.seal("access-token")))
    }

    @Test fun keyIsStableAcrossProviderInstances() {
        val sealed = SecretBox(KeystoreKeyProvider(alias)).seal("persisted")
        assertEquals("persisted", SecretBox(KeystoreKeyProvider(alias)).open(sealed))
    }

    @Test fun keyMaterialIsNotExportable() {
        val key = KeystoreKeyProvider(alias).key()
        assertEquals("AES", key.algorithm)
        assertNull(key.encoded)
    }
}
