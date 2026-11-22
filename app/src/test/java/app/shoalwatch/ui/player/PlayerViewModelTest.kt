package app.shoalwatch.ui.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.data.playback.CaptionDefaults
import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.PlaybackControllerTestBase
import app.shoalwatch.data.playback.Quality
import app.shoalwatch.data.playback.QualityDefault
import app.shoalwatch.data.playback.PlaybackState
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.SleepChoice
import app.shoalwatch.data.playback.StartFrom
import app.shoalwatch.data.playback.SubtitleSettings
import app.shoalwatch.data.playback.engine.EngineEvent
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.testServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The player screen's ViewModel over a real [app.shoalwatch.data.playback.PlaybackController] on the playback fakes
 * ([PlaybackControllerTestBase]): who owns playback, leaving when the session ends, the controls hiding, restored state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class PlayerViewModelTest : PlaybackControllerTestBase() {
    private val dispatcher = StandardTestDispatcher()
    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(account(BEN, "ben"), account(AMY, "amy")), account(BEN, "ben"))
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SubtitleSettings(context.getSharedPreferences("player_vm_test", Context.MODE_PRIVATE)) { CaptionDefaults(false, null, 1f) }
    private val made = mutableListOf<Session>()
    private val qualityPrefs = context.getSharedPreferences("player_vm_quality_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private var metered = false
    private val quality = QualityDefault(qualityPrefs) { metered }
    /** The connection seam: what Try again asks for, and what the probe then finds. */
    private val reprobed = mutableListOf<String>()
    private var onReprobe: () -> Unit = {}
    private val store = ViewModelStore()
    private val housekeeping = app.shoalwatch.ui.browse.FakeHousekeeping()
    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()
    /** While set, episode 1 plays from a copy saved on this phone (a LOCAL plan). */
    private var localCopy = false

    override val savedLookup: suspend (Session, String) -> app.shoalwatch.data.playback.SavedPlan? = { _, id ->
        if (!localCopy || id != ep1) null else {
            val folder = tmp.newFolder()
            val media = app.shoalwatch.data.downloads.SavedMedia(
                session.serverId, ep1, folder, java.io.File(folder, "media.mp4").apply { writeBytes(ByteArray(8)) }, hls = false,
                quality = app.shoalwatch.data.downloads.DownloadQuality.ORIGINAL, mediaSourceId = app.shoalwatch.data.playback.PlaybackFakes.SOURCE,
            )
            app.shoalwatch.data.playback.SavedPlan(media, app.shoalwatch.data.playback.SavedPlans.plan(ep1, media, emptyList(), runtime, hasSegments = false))
        }
    }

    @Before fun main() = Dispatchers.setMain(dispatcher)
    @After fun reset() = Dispatchers.resetMain()

    /** The profile's own "Play the next episode automatically", read as the route reads it (Milestone 7). */
    private val profileDao = app.shoalwatch.data.playback.FakeProfileDao()
    private val profilePlayback = app.shoalwatch.data.playback.ProfilePlayback(profileDao)

    private fun TestScope.vm(saved: SavedStateHandle = SavedStateHandle(), from: StartFrom = StartFrom.RESUME, settle: Boolean = true, source: String? = null, subtitles: app.shoalwatch.data.subtitles.RemoteSubtitles? = null): PlayerViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                // The controller runs on the test's background scope: its endless tick loops would keep a ViewModel scope from ever going idle.
                PlayerViewModel(ep1, from, saved, catalog, { s, scope -> made += s; controllerFor(s, backgroundScope, autoplayNext = profilePlayback.autoplayReader(s, scope)) }, settings, quality,
                    { serverId -> reprobed += serverId; onReprobe() }, housekeeping, mediaSourceId = source, subtitles = subtitles) as T
        }
        return ViewModelProvider.create(store, factory)[PlayerViewModel::class.java].also { if (settle) runCurrent() }
    }

    private fun TestScope.playing(vm: PlayerViewModel) {
        engine.durationMs = runtime
        engine.startPlaying(); runCurrent()
        assertEquals(Phase.PLAYING, vm.state.value.playback.phase)
    }

    private fun TestScope.switchTo(s: Session?) { catalog.activeFlow.value = s?.let { account(it, "x") }; runCurrent() }

    @Test fun theFirstSessionOwnsPlayback() = runTest(dispatcher) {
        val vm = vm()
        assertEquals(listOf(BEN), made)
        assertEquals(BEN, vm.state.value.session)
        assertEquals(1, engine.loads.size)
        playing(vm)
        assertEquals("Episode 101", vm.state.value.playback.title)
        assertFalse(vm.state.value.leave)
    }

    @Test fun switchingAccountEndsPlaybackAsTheOldSessionAndLeaves() = runTest(dispatcher) {
        val vm = vm(); playing(vm); at(30_000)
        activePlayback.sessionChanged(AMY) // the graph's own hook, as in the app; the screen must leave either way
        switchTo(AMY)
        val s = vm.state.value
        assertTrue(s.leave)
        assertTrue(engine.released)
        assertEquals(PlaybackState.CLOSED, s.playback)
        assertNull(s.playback.title); assertNull(s.playback.itemId); assertEquals(0L, s.playback.positionMs); assertNull(s.playback.next)
        assertEquals(listOf(BEN), made)
        assertEquals(listOf(session), reports.stopSessions)
    }

    @Test fun switchingAccountEndsPlaybackEvenBeforeTheGraphsHookRuns() = runTest(dispatcher) {
        val vm = vm(); playing(vm); at(30_000)
        switchTo(AMY)
        assertTrue(vm.state.value.leave); assertTrue(engine.released)
        assertEquals("the old account is still signed in: its stop is sent as itself", listOf(session), reports.stopSessions)
    }

    @Test fun signingOutEndsPlaybackSendsNothingAndLeaves() = runTest(dispatcher) {
        val vm = vm(); playing(vm); at(30_000)
        catalog.accountList.value = listOf(account(AMY, "amy"))
        switchTo(null)
        assertTrue(vm.state.value.leave); assertTrue(engine.released)
        assertTrue(reports.calls.none { it.startsWith("stopped") })
    }

    @Test fun anAccountRemovedWhileAnotherBecomesActiveSendsNothing() = runTest(dispatcher) {
        val vm = vm(); playing(vm); at(30_000)
        catalog.accountList.value = listOf(account(AMY, "amy"))
        switchTo(AMY)
        assertTrue(vm.state.value.leave); assertTrue(engine.released)
        assertTrue(reports.calls.none { it.startsWith("stopped") })
    }

    @Test fun nobodySignedInPlaysNothingAndLeaves() = runTest(dispatcher) {
        catalog.activeFlow.value = null
        val vm = vm()
        assertTrue(vm.state.value.leave)
        assertTrue(made.isEmpty()); assertTrue(engine.loads.isEmpty())
    }

    @Test fun aClosedControllerLeaves() = runTest(dispatcher) {
        val vm = vm(); playing(vm)
        activePlayback.end(BEN.serverId, BEN.userId); runCurrent()
        assertTrue(vm.state.value.leave)
    }

    @Test fun theControlsHideAfterFourSecondsOnlyWhilePlaying() = runTest(dispatcher) {
        val vm = vm()
        assertTrue(vm.state.value.controlsVisible)
        advanceTimeBy(5_000); runCurrent()
        assertTrue("not playing yet: stays", vm.state.value.controlsVisible)
        playing(vm)
        advanceTimeBy(3_900); runCurrent()
        assertTrue(vm.state.value.controlsVisible)
        advanceTimeBy(200); runCurrent()
        assertFalse(vm.state.value.controlsVisible)
        vm.tapSurface(); runCurrent()
        assertTrue(vm.state.value.controlsVisible)
        vm.playPause(); runCurrent()
        assertEquals(Phase.PAUSED, vm.state.value.playback.phase)
        advanceTimeBy(10_000); runCurrent()
        assertTrue("paused: stays", vm.state.value.controlsVisible)
    }

    @Test fun theControlsNeverHideWithTouchExplorationOrASheetOpen() = runTest(dispatcher) {
        val vm = vm(); playing(vm)
        vm.setTouchExploration(true)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(vm.state.value.controlsVisible)
        vm.setTouchExploration(false)
        vm.openSheet(PlayerSheet.SPEED)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(vm.state.value.controlsVisible)
        vm.closeSheet()
        advanceTimeBy(4_100); runCurrent()
        assertFalse(vm.state.value.controlsVisible)
    }

    @Test fun theOwnerAndThePositionAreSavedButNoTitle() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = vm(saved); playing(vm); at(42_000)
        assertEquals(BEN.serverId, saved.get<String>(PlayerViewModel.KEY_SERVER))
        assertEquals(BEN.userId, saved.get<String>(PlayerViewModel.KEY_USER))
        assertEquals(42_000L, saved.get<Long>(PlayerViewModel.KEY_POSITION))
        assertEquals(ep1, saved.get<String>(PlayerViewModel.KEY_ITEM))
        assertEquals(setOf(PlayerViewModel.KEY_SERVER, PlayerViewModel.KEY_USER, PlayerViewModel.KEY_ITEM, PlayerViewModel.KEY_POSITION), saved.keys())
    }

    @Test fun afterNextTheSavedPositionIsTheNewEpisodesAndNotTheOldOnes() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = vm(saved); playing(vm); at(500_000)
        vm.next(); runCurrent()
        assertEquals(ep2, saved.get<String>(PlayerViewModel.KEY_ITEM))
        assertNull("the old episode's position is not kept for the new one", saved.get<Long>(PlayerViewModel.KEY_POSITION))
        engine.startPlaying(); runCurrent(); at(600_000)
        assertEquals(600_000L, saved.get<Long>(PlayerViewModel.KEY_POSITION))
    }

    @Test fun restoredAfterUpNextReopensTheEpisodeThatWasPlayingAtItsPosition() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf(
            PlayerViewModel.KEY_SERVER to BEN.serverId, PlayerViewModel.KEY_USER to BEN.userId,
            PlayerViewModel.KEY_ITEM to ep2, PlayerViewModel.KEY_POSITION to 600_000L,
        ))
        val vm = vm(saved) // the route still names ep1, the episode first opened
        assertEquals(ep2, vm.state.value.playback.itemId)
        assertEquals(listOf(600_000L), engine.seeks)
    }

    @Test fun restoredForAnotherSessionPlaysNothingAndLeaves() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf(PlayerViewModel.KEY_SERVER to AMY.serverId, PlayerViewModel.KEY_USER to AMY.userId, PlayerViewModel.KEY_POSITION to 90_000L))
        val vm = vm(saved)
        assertTrue(vm.state.value.leave)
        assertTrue(made.isEmpty()); assertTrue(engine.loads.isEmpty())
    }

    @Test fun restoredForTheSameSessionGoesBackToTheSavedPosition() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf(PlayerViewModel.KEY_SERVER to BEN.serverId, PlayerViewModel.KEY_USER to BEN.userId, PlayerViewModel.KEY_ITEM to ep1, PlayerViewModel.KEY_POSITION to 90_000L))
        vm(saved)
        assertEquals(listOf(90_000L), engine.seeks)
    }

    @Test fun leavingTheScreenEndsPlaybackAndSendsTheStop() = runTest(dispatcher) {
        val vm = vm(); playing(vm); at(30_000)
        store.clear(); runCurrent()
        assertTrue(engine.released)
        assertEquals(listOf("stopped@30000"), stopCalls)
        assertFalse(vm.state.value.leave)
    }

    @Test fun theHostStoppingPauses() = runTest(dispatcher) {
        val vm = vm(); playing(vm)
        vm.onHostStopped(); runCurrent()
        assertEquals(Phase.PAUSED, vm.state.value.playback.phase)
        assertFalse(engine.isPlaying)
    }

    /** Milestone 3 carried n-2: a stop that comes before the controller exists is kept, so the first load starts paused. */
    @Test fun aHostStopBeforeTheControllerExistsIsKeptAndTheFirstLoadStartsPaused() = runTest(dispatcher) {
        val vm = vm(settle = false)
        vm.onHostStopped()
        runCurrent()
        assertEquals(1, engine.loads.size)
        assertFalse("the first load starts paused", engine.playWhenReady)
        assertTrue("a headset cannot start it either", engine.stoppedHost)
        vm.onHostStarted(); runCurrent()
        assertFalse(engine.stoppedHost)
    }

    @Test fun aHostStopAndStartBothBeforeTheControllerLeaveTheFirstLoadPlaying() = runTest(dispatcher) {
        val vm = vm(settle = false)
        vm.onHostStopped()
        vm.onHostStarted()
        runCurrent()
        assertEquals(1, engine.loads.size)
        assertTrue(engine.playWhenReady)
        assertFalse(engine.stoppedHost)
    }

    @Test fun aSleepTimerThatFiresIsSaidOnceButTurningItOffIsNot() = runTest(dispatcher) {
        val vm = vm(); playing(vm)
        vm.setSleep(SleepChoice.MIN_15); runCurrent()
        vm.setSleep(SleepChoice.OFF); runCurrent()
        assertFalse(vm.state.value.sleepFired)
        vm.setSleep(SleepChoice.MIN_15); runCurrent()
        clock.ms = 15 * 60_000L + 1
        advanceTimeBy(250); runCurrent()
        assertEquals(Phase.PAUSED, vm.state.value.playback.phase)
        assertTrue(vm.state.value.sleepFired)
        vm.dismissSleepFired()
        assertFalse(vm.state.value.sleepFired)
    }

    @Test fun theStreamLineShowsForFourSecondsAtTheStart() = runTest(dispatcher) {
        val vm = vm(); playing(vm)
        assertTrue(vm.state.value.streamInfoShown)
        advanceTimeBy(4_100); runCurrent()
        assertFalse(vm.state.value.streamInfoShown)
    }

    @Test fun tryAgainOpensTheItemAgain() = runTest(dispatcher) {
        val vm = vm(); playing(vm)
        engine.emit(EngineEvent.Failed(app.shoalwatch.data.playback.engine.EngineFailure.OTHER)); runCurrent()
        assertEquals(Phase.FAILED, vm.state.value.playback.phase)
        vm.retry(); runCurrent()
        assertEquals(2, engine.loads.size)
    }

    @Test fun tryAgainAsksForTheSameChosenVersionAgain() = runTest(dispatcher) {
        val b = "fedcba9876543210fedcba9876543210"
        gateway.failWith = AppError(AppError.Kind.OFFLINE, "offline")
        val vm = vm(source = b)
        assertEquals(Phase.FAILED, vm.state.value.playback.phase)
        gateway.failWith = null
        vm.retry(); runCurrent()
        assertEquals(listOf(b, b), gateway.bodies.map { it.mediaSourceId })
    }

    // ---- Find subtitles online (Milestone 11, Task 9)

    private val candidate = app.shoalwatch.data.subtitles.SubtitleCandidate("srt-eng-1", "OpenSubtitles", "Episode", "srt", null, null, hearingImpaired = false, forced = false)

    @Test fun aDownloadThatArrivesAsksTheServerAgainAtTheSamePositionWithTheSameVersion() = runTest(dispatcher) {
        val b = "fedcba9876543210fedcba9876543210"
        val remote = app.shoalwatch.ui.FakeRemoteSubtitles().also { it.found = listOf(candidate); it.count = 2 }
        val vm = vm(source = b, subtitles = remote); playing(vm); at(30_000)
        vm.findSubtitles(); runCurrent()
        assertEquals(PlayerSheet.SUBTITLE_SEARCH, vm.state.value.sheet)
        assertEquals(listOf(candidate), vm.state.value.search.candidates)
        assertEquals(1, engine.loads.size)
        vm.searchActions!!.onDownload(candidate); runCurrent()
        assertEquals("asked again", 2, engine.loads.size)
        assertEquals("at the same position", 30_000L, engine.loadedAt)
        assertEquals("the chosen version is kept", listOf(b, b), gateway.bodies.map { it.mediaSourceId })
        assertEquals(app.shoalwatch.R.string.subsearch_arrived, vm.state.value.search.message)
    }

    /** A remote whose arrival check waits until the test says (so the queue can move on first). */
    private class GatedRemote : app.shoalwatch.data.subtitles.RemoteSubtitles {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun search(s: Session, itemId: String, language: String) = app.shoalwatch.data.subtitles.SubtitleSearch.Found(emptyList())
        override suspend fun download(s: Session, itemId: String, subtitleId: String) = app.shoalwatch.data.subtitles.SubtitleDownload.Requested
        override suspend fun subtitleCount(s: Session, itemId: String) = 0
        override suspend fun awaitArrival(s: Session, itemId: String, countBefore: Int): app.shoalwatch.data.subtitles.Arrival { gate.await(); return app.shoalwatch.data.subtitles.Arrival.Arrived }
        override suspend fun preferredLanguage(s: Session) = "eng"
    }

    @Test fun subtitlesThatArriveAfterTheQueueMovedOnDoNotReloadTheNewItem() = runTest(dispatcher) {
        val remote = GatedRemote()
        val vm = vm(subtitles = remote); playing(vm); at(30_000)
        vm.findSubtitles(); runCurrent()
        vm.searchActions!!.onDownload(candidate); runCurrent()
        vm.next(); runCurrent()
        engine.startPlaying(); runCurrent()
        assertEquals(ep2, vm.state.value.playback.itemId)
        val loads = engine.loads.size
        val infos = playbackInfos
        remote.gate.complete(Unit); runCurrent()
        assertEquals("the new episode is not stopped and asked for again", loads, engine.loads.size)
        assertEquals(infos, playbackInfos)
    }

    @Test fun aPausedPlayerStaysPausedAtTheSamePositionWhenSubtitlesArriveWithNoPlayingInBetween() = runTest(dispatcher) {
        val remote = app.shoalwatch.ui.FakeRemoteSubtitles().also { it.found = listOf(candidate); it.count = 2 }
        val vm = vm(subtitles = remote); playing(vm); at(30_000)
        vm.playPause(); runCurrent()
        assertEquals(Phase.PAUSED, vm.state.value.playback.phase)
        val phases = mutableListOf<Phase>()
        val watch = backgroundScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { vm.state.collect { phases += it.playback.phase } }
        vm.findSubtitles(); runCurrent()
        vm.searchActions!!.onDownload(candidate); runCurrent()
        assertEquals(2, engine.loads.size)
        assertEquals(30_000L, engine.loadedAt)
        assertFalse("loaded paused", engine.playWhenReady)
        engine.emit(EngineEvent.Ready); runCurrent() // the engine reports ready; nothing asked it to play
        assertFalse("never PLAYING in between: $phases", phases.contains(Phase.PLAYING))
        assertEquals(Phase.PAUSED, vm.state.value.playback.phase)
        vm.playPause(); runCurrent()
        assertTrue("Play still works", engine.playWhenReady)
        watch.cancel()
    }

    @Test fun anArrivalForAnotherItemSaysTheyWillBeThereNextTime() = runTest(dispatcher) {
        val remote = GatedRemote()
        val vm = vm(subtitles = remote); playing(vm); at(30_000)
        vm.findSubtitles(); runCurrent()
        vm.searchActions!!.onDownload(candidate); runCurrent()
        vm.next(); runCurrent()
        remote.gate.complete(Unit); runCurrent()
        assertEquals(app.shoalwatch.R.string.subsearch_arrived_elsewhere, vm.state.value.search.message)
    }

    @Test fun aDownloadThatHasNotArrivedDoesNotReload() = runTest(dispatcher) {
        val remote = app.shoalwatch.ui.FakeRemoteSubtitles().also { it.found = listOf(candidate); it.arrival = app.shoalwatch.data.subtitles.Arrival.NotYet }
        val vm = vm(subtitles = remote); playing(vm); at(30_000)
        vm.findSubtitles(); runCurrent()
        vm.searchActions!!.onDownload(candidate); runCurrent()
        assertEquals(1, engine.loads.size)
        assertEquals(app.shoalwatch.R.string.subsearch_later, vm.state.value.search.message)
    }

    @Test fun aRefusalKeepsThePlayerGoingAndTheSignIn() = runTest(dispatcher) {
        val remote = app.shoalwatch.ui.FakeRemoteSubtitles().also { it.notAllowed = true }
        val vm = vm(subtitles = remote); playing(vm)
        vm.findSubtitles(); runCurrent()
        assertEquals(app.shoalwatch.ui.subtitles.SubtitleSearchPhase.NOT_ALLOWED, vm.state.value.search.phase)
        assertFalse(vm.state.value.leave)
        assertEquals(Phase.PLAYING, vm.state.value.playback.phase)
    }

    @Test fun backReturnsToTheSubtitlesSheetAndClosingTheSheetEndsTheSearch() = runTest(dispatcher) {
        val vm = vm(subtitles = app.shoalwatch.ui.FakeRemoteSubtitles()); playing(vm)
        vm.findSubtitles(); runCurrent()
        vm.backFromSearch(); runCurrent()
        assertEquals(PlayerSheet.TRACKS, vm.state.value.sheet)
        assertFalse(vm.state.value.search.open)
        vm.findSubtitles(); runCurrent(); vm.closeSheet(); runCurrent()
        assertFalse(vm.state.value.search.open)
        assertNull(vm.state.value.sheet)
    }

    @Test fun withoutTheSeamThereIsNoSearch() = runTest(dispatcher) {
        val vm = vm(); playing(vm)
        assertNull(vm.searchActions)
        vm.findSubtitles()
        assertNull(vm.state.value.sheet)
    }

    @Test fun lockingKeepsTheControlsFromHidingNothingButTogglesIt() = runTest(dispatcher) {
        val vm = vm()
        vm.toggleLock(); assertTrue(vm.state.value.locked)
        vm.toggleLock(); assertFalse(vm.state.value.locked)
    }

    // ---- the default quality (final review I-2)

    @Test fun onMobileDataWithNothingSavedTheFilmStartsAtEightMegabits() = runTest(dispatcher) {
        metered = true
        val vm = vm()
        assertEquals(8_000_000, gateway.bodies.first().maxStreamingBitrate)
        assertEquals(Quality.MBPS_8, vm.state.value.playback.quality)
    }

    @Test fun onAnUnmeteredNetworkWithNothingSavedTheFilmStartsAtTheBest() = runTest(dispatcher) {
        vm()
        assertEquals(Quality.MAXIMUM.maxBitrate, gateway.bodies.first().maxStreamingBitrate)
    }

    @Test fun aQualityChosenInTheSheetIsTheNextFilmsStart() = runTest(dispatcher) {
        val vm = vm(); playing(vm)
        vm.setQuality(Quality.MBPS_4); runCurrent()
        assertEquals(Quality.MBPS_4, quality.start())
        metered = true
        assertEquals("a saved choice wins on mobile data too", Quality.MBPS_4, quality.start())
    }

    // ---- Try again re-probes the server first (found on the emulator)

    @Test fun tryAgainAsksForAFreshProbeBeforeOpeningSoPlaybackStartsOnceTheServerIsBack() = runTest(dispatcher) {
        gateway.failWith = AppError(AppError.Kind.OFFLINE, "offline")
        val vm = vm()
        assertEquals(Phase.FAILED, vm.state.value.playback.phase)
        val asksBefore = playbackInfos
        var asksAtProbe = -1
        onReprobe = { asksAtProbe = playbackInfos; gateway.failWith = null } // the probe finds the server Online again
        vm.retry(); runCurrent()
        assertEquals(listOf(SERVER_ID), reprobed)
        assertEquals("the probe is asked for before the item is opened again", asksBefore, asksAtProbe)
        assertTrue(playbackInfos > asksBefore)
        engine.durationMs = runtime
        engine.startPlaying(); runCurrent()
        assertEquals(Phase.PLAYING, vm.state.value.playback.phase)
    }

    @Test fun theHostStoppingRefusesAHeadsetsPlayUntilItStartsAgain() = runTest(dispatcher) {
        val vm = vm(); playing(vm)
        vm.onHostStopped(); runCurrent()
        engine.controllerPlay(); runCurrent()
        assertEquals("final review N-4: no background playback", Phase.PAUSED, vm.state.value.playback.phase)
        vm.onHostStarted(); runCurrent()
        engine.controllerPlay(); runCurrent()
        assertEquals(Phase.PLAYING, vm.state.value.playback.phase)
    }

    // ---- watch-and-delete (Milestone 5)

    @Test fun aSavedCopyPlayedPastTheMarkOffersThePromptAndDeleteReleasesAsTheOwnerWithoutStoppingPlayback() = runTest(dispatcher) {
        localCopy = true
        val vm = vm(); playing(vm)
        assertEquals(app.shoalwatch.data.playback.PlayMethod.LOCAL, vm.state.value.playback.method)
        at(1_079_000); assertNull(vm.state.value.watched)
        at(1_081_000); runCurrent()
        assertEquals(WatchedPromptUi(BEN, ep1, film = false, together = 0, always = false), vm.state.value.watched)
        vm.watchedDelete(); runCurrent()
        assertEquals(listOf("release:ben:$ep1:false"), housekeeping.calls.filter { it.startsWith("release") })
        assertNull(vm.state.value.watched)
        assertEquals(WatchedMessage.DELETED, vm.state.value.watchedMessage)
        assertEquals(Phase.PLAYING, vm.state.value.playback.phase)
        vm.dismissWatchedMessage()
        assertNull(vm.state.value.watchedMessage)
    }

    @Test fun aStreamPlayedPastTheMarkOffersNothing() = runTest(dispatcher) {
        val vm = vm(); playing(vm)
        at(1_079_000); at(1_081_000); runCurrent()
        assertNull(vm.state.value.watched)
        assertEquals(emptyList<String>(), housekeeping.calls)
    }

    @Test fun keepDownloadDropsItForGood() = runTest(dispatcher) {
        localCopy = true
        val vm = vm(); playing(vm)
        at(1_079_000); at(1_081_000); runCurrent()
        vm.keepDownload(); runCurrent(); assertNull(vm.state.value.watched)
        at(1_082_000); runCurrent()
        assertNull("offered once per item", vm.state.value.watched)
        assertEquals(emptyList<String>(), housekeeping.calls.filter { it.startsWith("release") })
    }

    @Test fun switchingAccountDropsThePromptAndALatePressActsAsNobody() = runTest(dispatcher) {
        localCopy = true
        val vm = vm(); playing(vm)
        at(1_079_000); at(1_081_000); runCurrent()
        assertEquals(BEN, vm.state.value.watched?.session)
        switchTo(AMY)
        assertNull(vm.state.value.watched)
        vm.watchedDelete(); vm.watchedTogether(); runCurrent()
        assertEquals(emptyList<String>(), housekeeping.calls.filter { it.startsWith("release") })
    }

    // ---- "Play the next episode automatically" (Milestone 7)

    @Test fun withTheProfilesAutoplayOffUpNextWaitsForATap() = runTest(dispatcher) {
        profileDao.setAutoplay(BEN, false)
        val vm = vm(); playing(vm)
        engine.positionMs = runtime; engine.isPlaying = false; engine.emit(EngineEvent.Ended); runCurrent()
        assertEquals(ep2, vm.state.value.playback.upNext?.itemId)
        assertNull("no countdown", vm.state.value.playback.upNext?.secondsLeft)
        advanceTimeBy(20_000); runCurrent()
        assertEquals("nothing played by itself", ep1, vm.state.value.playback.itemId)
        assertEquals(1, engine.loads.size)
    }

    @Test fun withTheProfilesAutoplayOnUpNextCountsDownAndPlays() = runTest(dispatcher) {
        profileDao.setAutoplay(AMY, false) // another profile's choice changes nothing for ben
        val vm = vm(); playing(vm)
        engine.positionMs = runtime; engine.isPlaying = false; engine.emit(EngineEvent.Ended); runCurrent()
        assertEquals(10, vm.state.value.playback.upNext?.secondsLeft)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(ep2, vm.state.value.playback.itemId)
    }
}
