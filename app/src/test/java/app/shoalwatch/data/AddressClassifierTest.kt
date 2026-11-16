package app.shoalwatch.data

import app.shoalwatch.data.servers.looksLikeHome
import app.shoalwatch.net.ServerAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class AddressClassifierTest {
    private fun home(text: String) = ServerAddress.parse(text).looksLikeHome()

    @Test fun privateRangesAreHome() {
        listOf("192.168.1.10:8096", "10.0.0.5", "172.16.0.1", "172.31.255.1", "169.254.1.1", "127.0.0.1:8096", "http://[fd12::1]:8096", "http://[fe80::1]").forEach {
            assertEquals(it, true, home(it))
        }
    }

    @Test fun localNamesAndSingleLabelHostsAreHome() {
        listOf("jellyfin.local", "nas", "http://jellyfin:8096", "localhost:8096").forEach { assertEquals(it, true, home(it)) }
    }

    @Test fun publicAddressesAreAway() {
        listOf("jellyfin.example.org", "8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.1.1", "https://media.example.co.uk:8920", "http://[2001:db8::1]").forEach {
            assertEquals(it, false, home(it))
        }
    }
}
