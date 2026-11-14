package app.offlinefin.net

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeyChainKeyManagerTest {
    private val km = KeyChainKeyManager(ApplicationProvider.getApplicationContext(), "my-alias")

    @Test fun `only the configured alias is offered`() {
        assertEquals("my-alias", km.chooseClientAlias(arrayOf("RSA"), null, null))
        assertArrayEquals(arrayOf("my-alias"), km.getClientAliases("RSA", null))
        assertNull(km.chooseServerAlias("RSA", null, null))
        assertNull(km.getServerAliases("RSA", null))
    }

    @Test fun `other aliases yield no key or chain`() {
        assertNull(km.getPrivateKey("someone-else"))
        assertNull(km.getCertificateChain("someone-else"))
    }
}
