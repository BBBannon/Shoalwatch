package app.offlinefin.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.offlinefin.data.servers.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceIdentityTest {
    @Test fun idIsGeneratedOnceAndStable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = DeviceIdentity.load(context)
        val second = DeviceIdentity.load(context)
        assertEquals(first.deviceId, second.deviceId)
        assertFalse(first.deviceId.isBlank())
        assertEquals("OfflineFin", first.clientName)
        assertFalse(first.toString().contains(first.deviceId))
    }
}
