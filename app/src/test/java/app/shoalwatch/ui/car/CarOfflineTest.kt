package app.shoalwatch.ui.car

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.downloads.DownloadInfo
import app.shoalwatch.data.downloads.DownloadPaths
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.FakeVolumes
import app.shoalwatch.data.downloads.OfflineEntry
import app.shoalwatch.data.downloads.OfflineLibrary
import app.shoalwatch.data.downloads.SavedMedia
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.Home
import app.shoalwatch.data.library.HomeRow
import app.shoalwatch.data.playback.PlayMethod
import app.shoalwatch.data.playback.PlaybackControllerTestBase
import app.shoalwatch.data.playback.PlaybackFakes
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.Quality
import app.shoalwatch.data.playback.SavedPlan
import app.shoalwatch.data.playback.SavedPlans
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.Judged
import app.shoalwatch.security.LockState
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FakeDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Milestone 9: with no connection the car still lists the downloads and plays them, through the phone's own offline path (the
 * real resolver prefers the saved copy; the server is never asked). A verified copy of episode 1 for the first profile, the server offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CarOfflineTest : PlaybackControllerTestBase() {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val downloads = FakeDownloads()
    private val store = ViewModelStore()
    private lateinit var media: SavedMedia

    override val savedLookup: suspend (Session, String) -> SavedPlan? = { s, id ->
        if (s != session || id != ep1) null else SavedPlan(media, SavedPlans.plan(id, media, emptyList(), runtime, hasSegments = false))
    }

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val paths = DownloadPaths(FakeVolumes(tmp.root).volumes)
        val folder = paths.itemFolder(DownloadKey(session.serverId, ep1), StorageKind.INTERNAL, null)!!.also { it.mkdirs() }
        val file = paths.mediaFile(folder, "mp4").apply { writeBytes(ByteArray(64)) }
        media = SavedMedia(session.serverId, ep1, folder, file, hls = false, quality = DownloadQuality.ORIGINAL, mediaSourceId = PlaybackFakes.SOURCE)
        gateway.failWith = AppError.of(AppError.Kind.OFFLINE)
        val offline = Freshness.Stale(AppError.Kind.OFFLINE)
        library.homeFlow(session).value = Home(emptyList(), offline, HomeRow(emptyList(), offline), HomeRow(emptyList(), offline), emptyList())
        val info = DownloadInfo(ep1, DownloadState.COMPLETE, null, DownloadQuality.ORIGINAL, 1f, 64, true, StorageKind.INTERNAL, true)
        downloads.libraryFlow(session).value = OfflineLibrary(emptyList(), listOf(OfflineEntry(episode(101), info)), emptyList())
    }

    @After fun reset() { store.clear(); Dispatchers.resetMain() }

    private fun TestScope.vm(): CarViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = CarViewModel(
                MutableStateFlow(BEN), MutableStateFlow(LockState.Open), MutableStateFlow(Judged(BEN)), library, downloads,
                { s, _ -> controllerFor(s, backgroundScope) }, { Quality.MAXIMUM },
            ) as T
        }
        return ViewModelProvider.create(store, factory)[CarViewModel::class.java].also { it.setOnPhoneDisplay(false); runCurrent() }
    }

    @Test fun withNoConnectionTheDownloadIsListedAndPlaysFromThePhone() = runTest(dispatcher) {
        val vm = vm()
        val lists = vm.lists.value!!
        assertTrue("says it is offline", lists.offline)
        assertTrue(lists.resume.isEmpty() && lists.nextUp.isEmpty())
        assertEquals(listOf(ep1), lists.downloads.map { it.item.id })

        vm.play(ep1); runCurrent()
        engine.durationMs = runtime
        engine.startPlaying(); runCurrent()
        assertSame("the saved file", media, engine.loads.single().first.saved)
        assertNull(engine.loads.single().first.route)
        assertEquals(0, playbackInfos)
        assertTrue("no route asked for", routesMade.isEmpty())
        assertEquals(PlayMethod.LOCAL, vm.player.value?.playback?.method)
        assertEquals(Phase.PLAYING, vm.player.value?.playback?.phase)
    }
}
