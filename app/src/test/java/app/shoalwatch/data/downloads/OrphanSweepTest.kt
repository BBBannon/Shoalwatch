package app.shoalwatch.data.downloads

import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.testId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The orphan sweep removes exactly what no saved server, download or pinned picture accounts for, on every mounted root. */
class OrphanSweepTest {
    @get:Rule val tmp = TemporaryFolder()

    private val card by lazy { tmp.newFolder("card") }
    private val fake by lazy { FakeVolumes(tmp.root).card("CARD-1", card) }
    private val paths by lazy { DownloadPaths(fake.volumes) }
    private val s1 = testId("s1")
    private val gone = testId("gone-server")
    private val kept = testId("kept")
    private val orphan = testId("orphan")
    private val internal get() = fake.volumes.root(StorageKind.INTERNAL, null)!!
    private val sd get() = fake.volumes.root(StorageKind.REMOVABLE, "CARD-1")!!

    private fun file(f: File, text: String = "x") = f.also { it.parentFile.mkdirs(); it.writeText(text) }

    private fun folder(root: File, server: String, item: String) = File(File(root, server), item).also { it.mkdirs() }

    private val keptArt = ArtworkKey(s1, kept, ArtworkSlot.PRIMARY, "tag1")

    private fun sweep() = paths.sweep(setOf(DownloadKey(s1, kept)), setOf(s1), setOf(keptArt))

    @Test fun theFourDeletionsAndEverythingKnownIsKept() {
        val keptFolder = folder(internal, s1, kept)
        val media = file(File(keptFolder, "media.mkv"))
        val part = file(File(keptFolder, "media.mp4.part"))
        val sub = file(File(keptFolder, "sub-3.srt"))
        val seg = file(File(File(keptFolder, "hls"), "seg-00042.ts"))
        val playlist = file(File(File(keptFolder, "hls"), "local.m3u8"))
        val art = file(paths.artworkFile(s1, kept, ArtworkSlot.PRIMARY, "tag1"))
        val goneServer = folder(internal, gone, kept)
        val orphanItem = folder(internal, s1, orphan)
        val orphanArt = file(paths.artworkFile(s1, orphan, ArtworkSlot.BACKDROP, "tag2"))
        val stray = listOf(
            file(File(internal, "notes.txt")), file(File(File(internal, s1), "readme")), file(File(keptFolder, "media.exe")),
            file(File(keptFolder, "sub-3.txt")), file(File(File(keptFolder, "hls"), "seg-1.ts")), file(File(File(internal, s1), "art/$kept-LOGO-tag1.img")),
        )
        val result = sweep()
        assertTrue(listOf(media, part, sub, seg, playlist, art, File(internal, AndroidVolumes.NOMEDIA)).all { it.exists() })
        assertFalse("a server that is not saved", goneServer.parentFile.exists())
        assertFalse("a folder with no download row", orphanItem.exists())
        assertFalse("art with no pinned_artwork row", orphanArt.exists())
        for (f in stray) assertFalse("a name outside the layout: ${f.name}", f.exists())
        assertEquals(3 + stray.size, result.deleted)
        assertFalse(result.stoppedAtLimit)
    }

    @Test fun aSegmentBeingWrittenIsKeptButOtherPartNamesInHlsAreNot() {
        val hls = paths.hlsFolder(folder(internal, s1, kept)).also { it.mkdirs() }
        val writing = file(paths.segmentPartFile(hls, 7))
        val stray = listOf(file(File(hls, "local.m3u8.part")), file(File(hls, "seg-7.ts.part")), file(File(hls, "seg-00007.part")))
        sweep()
        assertTrue("seg-00007.ts.part is the fetcher's own name", writing.exists())
        for (f in stray) assertFalse(f.name, f.exists())
    }

    @Test fun aSubtitleBeingWrittenIsKeptButAStrayPartIsNot() {
        val keptFolder = folder(internal, s1, kept)
        val writing = file(paths.subtitlePartFile(keptFolder, 3, "srt"))
        val stray = listOf(file(File(keptFolder, "sub-3.part")), file(File(keptFolder, "sub-3.txt.part")), file(File(keptFolder, "other.part")))
        sweep()
        assertTrue(writing.exists())
        for (f in stray) assertFalse(f.name, f.exists())
    }

    @Test fun aFileNamedLikeAGuidButOutsideThePatternsIsDeleted() {
        val asFile = file(File(internal, s1)) // the server id, but a file rather than a folder
        sweep()
        assertFalse(asFile.exists())
        val keptFolder = folder(internal, s1, kept)
        val guidFile = file(File(keptFolder, testId("x")))
        val itemAsFile = file(File(File(internal, s1), orphan))
        sweep()
        assertFalse(guidFile.exists())
        assertFalse(itemAsFile.exists())
        assertTrue(keptFolder.exists())
    }

    @Test fun theCardIsSweptTooButNeverKeepsArtwork() {
        val onCard = folder(sd, s1, kept)
        val orphanOnCard = folder(sd, s1, orphan)
        val artOnCard = file(File(File(File(sd, s1), "art"), "$kept-PRIMARY-tag1.img"))
        sweep()
        assertTrue(onCard.exists())
        assertFalse(orphanOnCard.exists())
        assertFalse("artwork lives on internal storage only", artOnCard.exists())
    }

    @Test fun anUnmountedCardIsLeftAloneAndARootThatVanishesDoesNotThrow() {
        val orphanOnCard = folder(sd, s1, orphan)
        fake.unmount("CARD-1")
        sweep()
        assertTrue(orphanOnCard.exists())
        fake.mount("CARD-1")
        sd.deleteRecursively() // the card's folder disappears between listing the roots and reading them
        val vanishing = object : Volumes by fake.volumes {
            override fun allRoots() = listOf(File(card, "gone-root"), internal)
        }
        val orphanInternal = folder(internal, s1, orphan)
        DownloadPaths(vanishing).sweep(setOf(DownloadKey(s1, kept)), setOf(s1), emptySet())
        assertFalse("the other root is still swept", orphanInternal.exists())
    }

    @Test fun aLinkInTheTreeIsRemovedAsItselfAndItsTargetSurvives() {
        val target = tmp.newFolder("outside").also { file(File(it, "keep.txt")) }
        val keptFolder = folder(internal, s1, kept)
        assumeTrue("this machine cannot make a link", DownloadPathsTest.link(File(keptFolder, "hls"), target))
        val asServer = File(internal, gone)
        assumeTrue(DownloadPathsTest.link(asServer, target))
        sweep()
        assertFalse(File(keptFolder, "hls").exists())
        assertFalse(asServer.exists())
        assertTrue(File(target, "keep.txt").exists())
    }

    @Test fun theSweepStopsAtItsEntryLimit() {
        val keptFolder = folder(internal, s1, kept)
        val hls = File(keptFolder, "hls").also { it.mkdirs() }
        repeat(150) { File(hls, "junk-$it").createNewFile() }
        val result = DownloadPaths(fake.volumes, sweepLimit = 100).sweep(setOf(DownloadKey(s1, kept)), setOf(s1), emptySet())
        assertTrue(result.stoppedAtLimit)
        assertTrue("it stopped before looking at everything", hls.list()!!.size >= 50)
        assertEquals("the real limit", 100_000, DownloadPaths.SWEEP_LIMIT)
    }
}
