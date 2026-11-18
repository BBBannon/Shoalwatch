package app.shoalwatch

import org.junit.Assert.assertEquals
import org.junit.Test

/** Milestone 8: the first release is 1.0, build 1 (the version sent to the server in every request's client header). */
class ReleaseVersionTest {
    @Test fun theFirstReleaseIsOnePointZeroBuildOne() {
        assertEquals("1.0", BuildConfig.VERSION_NAME)
        assertEquals(1, BuildConfig.VERSION_CODE)
    }
}
