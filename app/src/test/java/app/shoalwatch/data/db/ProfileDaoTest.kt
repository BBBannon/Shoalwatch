package app.shoalwatch.data.db

import app.cash.turbine.test
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.db.profile.ProfileKeyRow
import app.shoalwatch.data.db.profile.ProfileLockEntity
import app.shoalwatch.data.db.profile.ProfilePlaybackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schema v6 (Milestone 7): profile locks and per-profile playback settings, in in-memory Room. A is (S1, U1), B is (S1, U2). */
class ProfileDaoTest : LibraryTestBase() {
    private val dao by lazy { db.profileDao() }
    private val first = byteArrayOf(1, 2, 3, 4)
    private val second = byteArrayOf(9, 8, 7)

    @Test fun insertLockTwiceReturnsARowIdThenMinusOneAndKeepsTheFirstVerifier() = runBlocking {
        assertNotEquals(-1L, dao.insertLock(S1, U1, first))
        assertEquals(-1L, dao.insertLock(S1, U1, second))
        val lock = dao.lock(S1, U1)!!
        assertArrayEquals(first, lock.verifier)
        assertEquals(0, lock.failures)
    }

    @Test fun observeProtectedFollowsInsertAndDelete() = runBlocking {
        dao.observeProtected().test {
            assertEquals(emptyList<ProfileKeyRow>(), awaitItem())
            dao.insertLock(S1, U1, first)
            assertEquals(listOf(ProfileKeyRow(S1, U1)), awaitItem())
            assertEquals(1, dao.deleteLock(S1, U1))
            assertEquals(emptyList<ProfileKeyRow>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun replaceVerifierResetsFailures() = runBlocking {
        dao.insertLock(S1, U1, first)
        assertEquals(1, dao.setFailures(S1, U1, 7))
        assertEquals(7, dao.lock(S1, U1)!!.failures)
        assertEquals(1, dao.replaceVerifier(S1, U1, second))
        val lock = dao.lock(S1, U1)!!
        assertEquals(0, lock.failures)
        assertArrayEquals(second, lock.verifier)
        assertEquals("no row to update for an unprotected profile", 0, dao.setFailures(S1, U2, 1))
    }

    @Test fun deletingAnAccountRemovesItsLockAndPlaybackRowOnly() = runBlocking {
        dao.insertLock(S1, U1, first)
        dao.insertLock(S1, U2, second)
        dao.upsertPlayback(ProfilePlaybackEntity(S1, U1, autoplayNext = false))
        dao.upsertPlayback(ProfilePlaybackEntity(S1, U2, autoplayNext = false))
        db.accountDao().delete(S1, U1)
        assertNull(dao.lock(S1, U1))
        assertNull(dao.observeAutoplay(S1, U1).first())
        assertArrayEquals(second, dao.lock(S1, U2)!!.verifier)
        assertEquals(false, dao.observeAutoplay(S1, U2).first())
        assertEquals(1, count("profile_locks"))
        assertEquals(1, count("profile_playback"))
    }

    @Test fun observeAutoplayIsNullThenFollowsUpsertsWithoutADuplicateRow() = runBlocking {
        dao.observeAutoplay(S1, U1).test {
            assertNull(awaitItem())
            dao.upsertPlayback(ProfilePlaybackEntity(S1, U1, autoplayNext = false))
            assertEquals(false, awaitItem())
            dao.upsertPlayback(ProfilePlaybackEntity(S1, U1, autoplayNext = true))
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, count("profile_playback"))
    }

    @Test fun toStringsNameNoIdAndNoVerifierBytes() {
        val verifier = byteArrayOf(42, 43, 44)
        val text = ProfileLockEntity(S1, U1, verifier, 3).toString()
        assertEquals("ProfileLockEntity(<redacted>)", text)
        for (secret in listOf(S1, U1, verifier.contentToString(), verifier.joinToString("") { "%02x".format(it) }))
            assertFalse(text.contains(secret))
        assertEquals("ProfilePlaybackEntity(<redacted>)", ProfilePlaybackEntity(S1, U1, true).toString())
        assertEquals("ProfileKeyRow(<redacted>)", ProfileKeyRow(S1, U1).toString())
        assertTrue(ProfileKeyRow(S1, U1) == ProfileKeyRow(S1, U1))
    }
}
