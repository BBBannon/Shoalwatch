package app.shoalwatch.ui.subtitles

import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.subtitles.Arrival
import app.shoalwatch.data.subtitles.RemoteSubtitles
import app.shoalwatch.data.subtitles.SubtitleCandidate
import app.shoalwatch.data.subtitles.SubtitleDownload
import app.shoalwatch.data.subtitles.SubtitleSearch
import app.shoalwatch.ui.FakeRemoteSubtitles
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleSearchLogicTest {
    private val remote = FakeRemoteSubtitles()
    private var session: Session? = BEN
    private val arrived = mutableListOf<Session>()
    private val item = "0123456789abcdef0123456789abcdef"
    private val candidate = SubtitleCandidate("srt-eng-1", "OpenSubtitles", "The Film", "srt", 8.5f, 1200, hearingImpaired = true, forced = false)

    private fun TestScope.logic(r: RemoteSubtitles = remote) =
        SubtitleSearchLogic(this, r, { session }, { item }, { s, _ -> arrived += s })

    @Test fun itOpensInTheProfilesLanguageAndSearchesIt() = runTest {
        remote.language = "fre"; remote.found = listOf(candidate)
        val l = logic(); l.open(); advanceUntilIdle()
        assertEquals(listOf("preferredLanguage", "search:$item:fre"), remote.calls)
        assertEquals("fre", l.state.value.language)
        assertEquals(SubtitleSearchPhase.RESULTS, l.state.value.phase)
        assertEquals(listOf(candidate), l.state.value.candidates)
    }

    @Test fun aBadPreferredLanguageFallsBackToEnglish() = runTest {
        remote.language = "xx"
        val l = logic(); l.open(); advanceUntilIdle()
        assertEquals("eng", l.state.value.language)
    }

    @Test fun changingTheLanguageSearchesAgainAndOnlyThreeLetterCodesAreSent() = runTest {
        val l = logic(); l.open(); advanceUntilIdle()
        l.setLanguage("spa"); advanceUntilIdle()
        l.setLanguage("../x"); l.setLanguage("EN"); advanceUntilIdle()
        assertEquals(listOf("preferredLanguage", "search:$item:eng", "search:$item:spa"), remote.calls)
        assertEquals("spa", l.state.value.language)
        assertFalse(l.state.value.picking)
    }

    @Test fun noMatchesSaysSoAndARefusalIsTheSentenceFromThePlan() = runTest {
        val l = logic(); l.open(); advanceUntilIdle()
        assertEquals(SubtitleSearchPhase.RESULTS, l.state.value.phase)
        assertTrue(l.state.value.candidates.isEmpty())
        remote.notAllowed = true
        l.retry(); advanceUntilIdle()
        assertEquals(SubtitleSearchPhase.NOT_ALLOWED, l.state.value.phase)
    }

    @Test fun offlineIsSaidAndTryAgainWorks() = runTest {
        remote.failure = AppError(AppError.Kind.OFFLINE, "offline")
        val l = logic(); l.open(); advanceUntilIdle()
        assertEquals(SubtitleSearchPhase.OFFLINE, l.state.value.phase)
        remote.failure = null; remote.found = listOf(candidate)
        l.retry(); advanceUntilIdle()
        assertEquals(SubtitleSearchPhase.RESULTS, l.state.value.phase)
    }

    @Test fun anyOtherFailureIsAFailureNotOffline() = runTest {
        remote.failure = AppError(AppError.Kind.UNKNOWN, "bad")
        val l = logic(); l.open(); advanceUntilIdle()
        assertEquals(SubtitleSearchPhase.FAILED, l.state.value.phase)
    }

    @Test fun theCountIsReadBeforeTheDownloadAndHandedToTheArrivalCheck() = runTest {
        remote.count = 3; remote.found = listOf(candidate)
        val l = logic(); l.open(); advanceUntilIdle(); remote.calls.clear()
        l.download(candidate); advanceUntilIdle()
        assertEquals(listOf("subtitleCount:$item", "download:$item:srt-eng-1", "awaitArrival:$item:3"), remote.calls)
        assertEquals(listOf(BEN), arrived)
        assertEquals(R.string.subsearch_arrived, l.state.value.message)
        assertFalse(l.state.value.sending)
    }

    @Test fun ifItHasNotArrivedItSaysItCanTakeAMinuteAndNothingReloads() = runTest {
        remote.arrival = Arrival.NotYet
        val l = logic(); l.open(); advanceUntilIdle()
        l.download(candidate); advanceUntilIdle()
        assertEquals(R.string.subsearch_later, l.state.value.message)
        assertTrue(arrived.isEmpty())
    }

    @Test fun aRefusedDownloadKeepsEverythingAndSaysSo() = runTest {
        val l = logic(); l.open(); advanceUntilIdle()
        remote.notAllowed = true; remote.calls.clear()
        l.download(candidate); advanceUntilIdle()
        assertEquals(R.string.subsearch_refused, l.state.value.message)
        assertEquals("no arrival check after a refusal", listOf("subtitleCount:$item", "download:$item:srt-eng-1"), remote.calls)
        assertTrue(arrived.isEmpty())
    }

    @Test fun anOfflineDownloadSaysSoAndSendsNothing() = runTest {
        val l = logic(); l.open(); advanceUntilIdle()
        remote.failure = AppError(AppError.Kind.OFFLINE, "offline"); remote.calls.clear()
        l.download(candidate); advanceUntilIdle()
        assertEquals(R.string.subsearch_send_offline, l.state.value.message)
        assertEquals("the count failed first, so the request was never sent", listOf("subtitleCount:$item"), remote.calls)
    }

    /** A remote whose arrival check waits in virtual time, as the real one does (2, 4, 8, 16 seconds). */
    private class SlowRemote : RemoteSubtitles {
        val downloads = mutableListOf<String>()
        override suspend fun search(s: Session, itemId: String, language: String) = SubtitleSearch.Found(emptyList())
        override suspend fun download(s: Session, itemId: String, subtitleId: String): SubtitleDownload { downloads += subtitleId; return SubtitleDownload.Requested }
        override suspend fun subtitleCount(s: Session, itemId: String) = 1
        override suspend fun awaitArrival(s: Session, itemId: String, countBefore: Int): Arrival { delay(30_000); return Arrival.Arrived }
        override suspend fun preferredLanguage(s: Session) = "eng"
    }

    @Test fun aDownloadIsSentOnceWhileTheCheckRunsAndButtonsWaitInTheMeantime() = runTest {
        val slow = SlowRemote()
        val l = logic(slow); l.open(); runCurrent()
        l.download(candidate); runCurrent()
        assertTrue(l.state.value.sending)
        l.download(candidate); l.download(candidate.copy(id = "other")); runCurrent() // a double tap, and a second row
        advanceTimeBy(29_000); runCurrent()
        assertTrue("still checking", l.state.value.sending)
        assertTrue(arrived.isEmpty())
        advanceTimeBy(2_000); runCurrent()
        assertFalse(l.state.value.sending)
        assertEquals(listOf("srt-eng-1"), slow.downloads)
        assertEquals(listOf(BEN), arrived)
    }

    @Test fun anotherProfilesAnswerIsDroppedAndNeverReloads() = runTest {
        val slow = SlowRemote()
        val l = logic(slow); l.open(); runCurrent()
        l.download(candidate); runCurrent()
        session = AMY // the profile changed while it was checking
        advanceTimeBy(31_000); runCurrent()
        assertTrue(arrived.isEmpty())
        assertEquals(0, l.state.value.message)
    }

    @Test fun closingWhileItChecksStillReloadsWhenItArrives() = runTest {
        val slow = SlowRemote()
        val l = logic(slow); l.open(); runCurrent()
        l.download(candidate); runCurrent()
        l.close()
        assertFalse(l.state.value.open)
        assertTrue("a second download cannot start from a reopened window", l.state.value.sending)
        advanceTimeBy(31_000); runCurrent()
        assertEquals(listOf(BEN), arrived)
        assertEquals(0, l.state.value.message)
    }

    @Test fun thereAreReadableNamesForTheCodes() {
        assertEquals("English", SubtitleLanguages.name("eng", java.util.Locale.ENGLISH))
        assertEquals("French", SubtitleLanguages.name("fre", java.util.Locale.ENGLISH))
        assertEquals("German", SubtitleLanguages.name("ger", java.util.Locale.ENGLISH))
        assertEquals("a code Android does not know is shown as itself", "ZZZ", SubtitleLanguages.name("zzz", java.util.Locale.ENGLISH))
        assertTrue(SubtitleLanguages.codes("eng").all { it.length == 3 })
        assertEquals("tgl", SubtitleLanguages.codes("tgl").first())
    }

    @Test fun aLanguageIsListedOnceWhicheverOfItsTwoCodesTheProfileHasAndKeepsTheProfilesCode() {
        val pairs = listOf("fra" to "fre", "deu" to "ger", "zho" to "chi", "ces" to "cze", "nld" to "dut", "ell" to "gre", "ron" to "rum", "cym" to "wel")
        pairs.forEach { (t, b) ->
            val list = SubtitleLanguages.codes(t)
            assertEquals("$t listed once", 1, list.count { it == t || it == b })
            assertTrue("$t is the one sent", t in list && b !in list)
            assertEquals("same name for both", SubtitleLanguages.name(b, java.util.Locale.ENGLISH), SubtitleLanguages.name(t, java.util.Locale.ENGLISH))
        }
        assertEquals("the list's own code stays when the profile has that one", 1, SubtitleLanguages.codes("fre").count { it == "fre" })
        assertEquals(SubtitleLanguages.codes("eng").size, SubtitleLanguages.codes("fra").size)
    }

    @Test fun ifTheCountCannotBeReadNothingIsSentAndTheWordsDoNotSayADownloadFailed() = runTest {
        val l = logic(); l.open(); advanceUntilIdle()
        remote.failure = AppError(AppError.Kind.UNKNOWN, "x"); remote.calls.clear()
        l.download(candidate); advanceUntilIdle()
        assertEquals(R.string.subsearch_unreachable, l.state.value.message)
        assertEquals(listOf("subtitleCount:$item"), remote.calls)
        assertFalse(l.state.value.sending)
    }

    @Test fun theArrivalIsToldWhichItemItWasFor() = runTest {
        val got = mutableListOf<String>()
        val l = SubtitleSearchLogic(this, remote, { session }, { item }, { _, i -> got += i })
        l.open(); advanceUntilIdle()
        l.download(candidate); advanceUntilIdle()
        assertEquals(listOf(item), got)
    }
}
