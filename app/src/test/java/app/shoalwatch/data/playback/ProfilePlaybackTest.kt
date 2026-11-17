package app.shoalwatch.data.playback

import app.shoalwatch.data.db.LibraryTestBase
import app.shoalwatch.data.db.profile.ProfileDao
import app.shoalwatch.data.db.profile.ProfileKeyRow
import app.shoalwatch.data.db.profile.ProfileLockEntity
import app.shoalwatch.data.db.profile.ProfilePlaybackEntity
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Play the next episode automatically", per profile, over in-memory Room (Milestone 7 Task 7). A is (S1, U1), B is (S1, U2). */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePlaybackTest : LibraryTestBase() {
    private val playback by lazy { ProfilePlayback(db.profileDao()) }

    @Test fun itIsOnByDefault() = runBlocking {
        assertTrue(playback.autoplay(s1u1).first())
    }

    @Test fun offThenOnAgain() = runBlocking {
        playback.setAutoplay(s1u1, false)
        assertFalse(playback.autoplay(s1u1).first())
        playback.setAutoplay(s1u1, true)
        assertTrue(playback.autoplay(s1u1).first())
    }

    @Test fun eachProfileHasItsOwn() = runBlocking {
        playback.setAutoplay(s1u1, false)
        assertFalse(playback.autoplay(s1u1).first())
        assertTrue("B is untouched by A's change", playback.autoplay(s1u2).first())
        assertTrue("the same user on another server is another profile", playback.autoplay(s2u1).first())
    }

    @Test fun theReaderGivesTheLatestValueOnceTheFlowHasEmitted() = runTest {
        val dao = FakeProfileDao()
        val reader = ProfilePlayback(dao).autoplayReader(s1u1, backgroundScope)
        assertTrue("on until the first read", reader())
        dao.setAutoplay(s1u1, false); runCurrent()
        assertFalse(reader())
        assertTrue("another profile's row is not read", ProfilePlayback(dao).autoplayReader(s1u2, backgroundScope).also { runCurrent() }())
        dao.setAutoplay(s1u1, true); runCurrent()
        assertTrue(reader())
    }

    @Test fun removingTheAccountTakesItsSettingWithIt() = runBlocking {
        playback.setAutoplay(s1u1, false)
        db.accountDao().delete(s1u1.serverId, s1u1.userId)
        assertEquals("no row left: the default again", true, playback.autoplay(s1u1).first())
        assertEquals(0, count())
    }

    private fun count(): Int = db.query("SELECT COUNT(*) FROM profile_playback", null).use { it.moveToFirst(); it.getInt(0) }
}

/** Only the playback rows, in memory and observable; the lock methods are not used by [ProfilePlayback]. */
internal class FakeProfileDao : ProfileDao {
    private val rows = MutableStateFlow<Map<Session, Boolean>>(emptyMap())
    fun setAutoplay(s: Session, on: Boolean) { rows.value = rows.value + (s to on) }
    override fun observeAutoplay(serverId: String, userId: String): Flow<Boolean?> = rows.map { it[Session(serverId, userId)] }
    override suspend fun upsertPlayback(row: ProfilePlaybackEntity) { setAutoplay(Session(row.serverId, row.userId), row.autoplayNext) }
    override fun observeProtected(): Flow<List<ProfileKeyRow>> = error("not used")
    override suspend fun lock(serverId: String, userId: String): ProfileLockEntity? = error("not used")
    override suspend fun insertLock(serverId: String, userId: String, verifier: ByteArray): Long = error("not used")
    override suspend fun replaceVerifier(serverId: String, userId: String, verifier: ByteArray): Int = error("not used")
    override suspend fun setFailures(serverId: String, userId: String, failures: Int): Int = error("not used")
    override suspend fun deleteLock(serverId: String, userId: String): Int = error("not used")
}
