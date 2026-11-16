package app.shoalwatch.data.downloads

import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.data.db.downloads.DownloadKey
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The removal rules, through the real [app.shoalwatch.data.servers.ServerStore] as the app wires it. A = U1, B = U2, both on
 * S1 (B is the child). Nothing of a removed account's is left on disk that another profile could not already see.
 */
class RemovalRulesTest : DownloadHousekeepingTestBase() {
    private val a get() = s1u1
    private val b get() = s1u2
    private val film get() = item("film")
    private val other get() = item("other")

    @Test fun removingAServerRemovesItsRowsAndFoldersOnEveryRootAndLeavesAnotherServers() {
        queued(a, film)
        queued(s2u1, film)
        val internal = filesOf(film)
        val onCard = filesOf(film, StorageKind.REMOVABLE)
        val kept = filesOf(film, serverId = S2)
        runBlocking { hooked.removeServer(S1) }
        settle()
        assertNull(download(film))
        assertFalse(internal.exists())
        assertFalse(onCard.exists())
        assertFalse("the server's folder goes", File(internal.parentFile!!.parentFile!!.path).exists())
        assertNotNull(download(film, S2))
        assertTrue(kept.exists())
    }

    @Test fun aCardThatWasOutIsCleanedByTheNextStart() {
        queued(a, film)
        val onCard = filesOf(film, StorageKind.REMOVABLE)
        fake.unmount(CARD)
        runBlocking { hooked.removeServer(S1) }
        settle()
        assertTrue("not reachable while out", onCard.exists())
        fake.mount(CARD)
        runBlocking { housekeeping.onStart()!!.join() }
        assertFalse(onCard.exists())
    }

    @Test fun removingTheOnlyOwnerWithSharingOffDeletesTheDownloadItsFilesAndItsPins() {
        val e1 = ep("e1", 1, 1)
        queued(a, series, season1, e1)
        val files = filesOf(e1)
        assertTrue(pinned("e1") && pinned("season1") && pinned("show"))
        runBlocking { hooked.removeAccount(S1, a.userId) }
        settle()
        assertNull(download(e1))
        assertFalse(files.exists())
        assertFalse(pinned("e1") || pinned("season1") || pinned("show"))
    }

    @Test fun twoOwnersRemoveOneAndItIsKeptForTheOther() {
        queued(a, film)
        seen(b, film)
        runBlocking { store.add(b, listOf(shareDownload(film))) }
        val files = filesOf(film)
        runBlocking { hooked.removeAccount(S1, a.userId) }
        settle()
        assertEquals(listOf(b.userId), runBlocking { store.owners(DownloadKey(S1, film.id)) })
        assertTrue(files.exists())
    }

    @Test fun sharingOnAndBHasARecordSoBBecomesTheOwnerAndNothingIsDeleted() {
        queued(a, film)
        complete(film)
        runBlocking { store.setSharing(S1, true) }
        seen(b, film)
        confirm(b, film)
        val files = filesOf(film)
        runBlocking { hooked.removeAccount(S1, a.userId) }
        settle()
        assertEquals(listOf(b.userId), runBlocking { store.owners(DownloadKey(S1, film.id)) })
        assertTrue(files.exists())
    }

    /** The strict sharing rule: B was shown the film but the server never confirmed B may download it, so B does not inherit it. */
    @Test fun sharingOnAndBHasARecordButNoConfirmedPermissionSoItIsDeleted() {
        queued(a, film)
        complete(film)
        runBlocking { store.setSharing(S1, true) }
        seen(b, film)
        val files = filesOf(film)
        runBlocking { hooked.removeAccount(S1, a.userId) }
        settle()
        assertNull(download(film))
        assertFalse(files.exists())
    }

    @Test fun sharingOnButTheChildHasNoRecordSoItIsDeleted() {
        queued(a, film)
        complete(film)
        runBlocking { store.setSharing(S1, true) }
        seen(b, other) // B was shown other things, never the film
        val files = filesOf(film)
        runBlocking { hooked.removeAccount(S1, a.userId) }
        settle()
        assertNull(download(film))
        assertFalse("nothing left that B could not already see", files.exists())
    }

    @Test fun anExpiredSignInChangesNothing() {
        queued(a, film)
        val files = filesOf(film)
        runBlocking { hooked.invalidateToken(S1, a.userId) }
        settle()
        assertNotNull(download(film))
        assertEquals(listOf(a.userId), runBlocking { store.owners(DownloadKey(S1, film.id)) })
        assertTrue(files.exists())
    }

    @Test fun removingAnAccountWhileItsItemIsRunningCancelsItAndCleansUp() {
        queued(a, film)
        playable()
        files.steps += { c ->
            c.part.parentFile!!.mkdirs()
            c.part.writeBytes(ByteArray(100))
            runBlocking { hooked.removeAccount(S1, a.userId) }
            settle()
            c.progress.saved(100, FakeFiles.SIZE, "\"v1\"", 0)
            error("the fetch must not go on after its row is gone")
        }
        assertEquals(QueueRun.Drained, drain())
        assertNull(download(film))
        assertFalse(File(folder(film), "media.mkv.part").exists())
    }

    @Test fun startRequeuesInterruptedRowsSweepsOrphansAndKicks() {
        queued(a, film)
        runBlocking { store.update(DownloadKey(S1, film.id)) { it.copy(state = DownloadState.RUNNING) } }
        val orphan = File(paths.itemFolder(DownloadKey(S1, item("gone").id), StorageKind.INTERNAL, null)!!.apply { mkdirs() }, "media.mkv").apply { writeBytes(ByteArray(1)) }
        val mine = filesOf(film)
        runBlocking { housekeeping.onStart()!!.join() }
        assertEquals(DownloadState.QUEUED, download(film)!!.state)
        assertFalse("an item with no row is swept", orphan.exists())
        assertTrue(mine.exists())
        assertTrue(kicked())
        assertNull("once per process", housekeeping.onStart())
    }

    @Test fun picturesNoDownloadPinsAnyMoreGoWithTheirRows() {
        queued(a, film)
        queued(a, other)
        runBlocking {
            db.downloadDao().pinArtwork(S1, film.id, ArtworkSlot.PRIMARY, "tag1", 3, 0)
            db.downloadDao().pinArtwork(S1, other.id, ArtworkSlot.PRIMARY, "tag1", 3, 0)
        }
        val filmArt = paths.artworkFile(S1, film.id, ArtworkSlot.PRIMARY, "tag1").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(3)) }
        val otherArt = paths.artworkFile(S1, other.id, ArtworkSlot.PRIMARY, "tag1").apply { writeBytes(ByteArray(3)) }
        runBlocking { store.removeOwner(a, film.id) }
        runBlocking { hooked.removeAccount(S1, b.userId) } // any account change tidies the pictures
        settle()
        assertEquals(emptyMap<ArtworkSlot, String>(), runBlocking { db.downloadDao().pinnedArtwork(S1, film.id) })
        assertFalse(filmArt.exists())
        assertTrue("still pinned by its download", otherArt.exists())
    }
}
