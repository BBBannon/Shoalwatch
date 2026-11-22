package app.shoalwatch

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The version sent to the server in every request's client header. Milestone 8 was 1.0 (build 1), the phone-test fixes 1.0.1
 * (build 2); Milestone 9, the Android Auto parked app, is 1.1.0 (build 3); Milestone 10, managing the library, is 1.2.0 (build 4); Milestone 11, playlists and richer browsing, is 1.3.0 (build 5).
 */
class ReleaseVersionTest {
    @Test fun milestoneNineIsOnePointOneBuildThree() {
        assertEquals("1.3.0", BuildConfig.VERSION_NAME)
        assertEquals(5, BuildConfig.VERSION_CODE)
    }
}
