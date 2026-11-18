package app.shoalwatch.ui.player

import app.shoalwatch.core.AppError
import app.shoalwatch.data.downloads.AfterFinish
import app.shoalwatch.data.downloads.ProfileSettings
import app.shoalwatch.data.downloads.ReleaseChoice
import app.shoalwatch.data.downloads.ReleaseOutcome
import app.shoalwatch.data.downloads.SharedDelete
import app.shoalwatch.data.playback.PlayMethod
import app.shoalwatch.data.playback.PlaybackState
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FakeHousekeeping
import app.shoalwatch.ui.browse.id
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When the player offers "Watched — delete download", and what each press does (FakeHousekeeping, virtual time). */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchedPromptLogicTest {
    private val housekeeping = FakeHousekeeping()
    private val e1 = id(101)
    private val e2 = id(102)
    private val local = PlaybackState.EMPTY.copy(session = BEN, itemId = e1, method = PlayMethod.LOCAL, phase = PlaybackState.Phase.PLAYING)

    private fun TestScope.logic() = WatchedPromptLogic(housekeeping, backgroundScope)

    /** Plays [state] to just before the mark, then across it. */
    private fun TestScope.finish(logic: WatchedPromptLogic, state: PlaybackState = local) {
        logic.onState(state.copy(finished = false)); runCurrent()
        logic.onState(state.copy(finished = true)); runCurrent()
    }

    private fun TestScope.messages(logic: WatchedPromptLogic): List<WatchedMessage> {
        val got = mutableListOf<WatchedMessage>()
        backgroundScope.launch { logic.messages.toList(got) }
        runCurrent()
        return got
    }

    @Test fun askWithAReleasableDownloadOffersTheCard() = runTest {
        val l = logic()
        finish(l)
        assertEquals(WatchedPromptUi(BEN, e1, film = false, together = 0, always = false), l.prompt.value)
    }

    @Test fun alwaysOffersTheInformationLineOnly() = runTest {
        housekeeping.set(BEN, ProfileSettings(AfterFinish.ALWAYS))
        val l = logic()
        finish(l)
        assertEquals(true, l.prompt.value?.always)
        l.delete(); runCurrent()
        assertEquals("the release happens at the stop, not here", emptyList<String>(), housekeeping.calls.filter { it.startsWith("release") })
    }

    @Test fun neverOrNothingToReleaseOffersNothing() = runTest {
        housekeeping.set(BEN, ProfileSettings(AfterFinish.NEVER))
        val l = logic(); finish(l)
        assertNull(l.prompt.value)
        housekeeping.set(BEN, ProfileSettings())
        housekeeping.choices = { _, _ -> ReleaseChoice(false, false, SharedDelete.ANYONE, 0, false) }
        val m = logic(); finish(m)
        assertNull(m.prompt.value)
    }

    @Test fun aStreamNeverOffers() = runTest {
        val l = logic()
        finish(l, local.copy(method = PlayMethod.DIRECT_PLAY))
        assertNull(l.prompt.value)
        assertEquals(emptyList<String>(), housekeeping.calls)
    }

    @Test fun aSeekPastTheMarkNeverOffers() = runTest {
        val l = logic()
        l.onState(local); l.onState(local.copy(positionMs = 2_600_000)); runCurrent() // the controller leaves finished false after a seek
        assertNull(l.prompt.value)
    }

    @Test fun offeredOncePerItemAndAgainForTheNextEpisode() = runTest {
        val l = logic()
        finish(l)
        l.keep(); runCurrent()
        assertNull(l.prompt.value)
        finish(l) // the same item crossing the mark again (a seek back and play on)
        assertNull(l.prompt.value)
        finish(l, local.copy(itemId = e2))
        assertEquals(e2, l.prompt.value?.itemId)
    }

    @Test fun theItemChangingOrThePlayerClosingDropsIt() = runTest {
        val l = logic()
        finish(l)
        l.onState(local.copy(itemId = e2)); runCurrent()
        assertNull(l.prompt.value)
        val m = logic()
        finish(m)
        m.onState(PlaybackState.CLOSED); runCurrent()
        assertNull(m.prompt.value)
    }

    @Test fun aSessionChangeMidPromptDropsItAndALatePressActsAsNobody() = runTest {
        val l = logic()
        finish(l)
        l.onState(local.copy(session = AMY)); runCurrent()
        assertNull(l.prompt.value)
        l.delete(); l.together(); runCurrent()
        assertEquals(emptyList<String>(), housekeeping.calls.filter { it.startsWith("release") })
    }

    @Test fun deleteAndTogetherReleaseAsThePromptsOwnSession() = runTest {
        housekeeping.choices = { _, _ -> ReleaseChoice(true, true, SharedDelete.EVERYONE, 2, false) }
        val l = logic()
        finish(l)
        assertEquals(2, l.prompt.value?.together)
        l.delete(); runCurrent()
        assertNull(l.prompt.value)
        finish(l, local.copy(itemId = e2))
        l.together(); runCurrent()
        assertEquals(listOf("release:ben:$e1:false", "release:ben:$e2:true"), housekeeping.calls.filter { it.startsWith("release") })
    }

    // --- security review M8 F12: a protected sharer is marked only after its own PIN, asked here -----------------------

    private val ann = app.shoalwatch.data.servers.AccountSummary(BEN.serverId, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "ann")

    @Test fun togetherAsksAProtectedSharersPinFirstAndAWrongOneIsAskedAgain() = runTest {
        housekeeping.choices = { _, _ -> ReleaseChoice(true, true, SharedDelete.EVERYONE, 1, false) }
        housekeeping.protectedSharers = listOf(ann)
        val l = logic()
        val got = messages(l)
        finish(l)
        l.together(); runCurrent()
        assertEquals("ann", l.sharerPin.value?.name)
        assertEquals("nothing released yet", emptyList<String>(), housekeeping.calls.filter { it.startsWith("release") })
        l.pin.edit { replace(0, length, "1111") }
        l.submitSharerPin(); runCurrent()
        assertEquals(app.shoalwatch.security.PinCheck.Wrong(4), l.sharerPin.value?.answer)
        assertEquals("", l.pin.text.toString())
        l.pin.edit { replace(0, length, "4826") }
        l.submitSharerPin(); runCurrent()
        assertNull(l.sharerPin.value)
        assertEquals(listOf("prove:ben:${ann.userId}:$e1:1111", "prove:ben:${ann.userId}:$e1:4826", "release:ben:$e1:true"),
            housekeeping.calls.filter { it.startsWith("prove") || it.startsWith("release") })
        assertEquals(listOf(WatchedMessage.DELETED), got)
    }

    @Test fun cancellingTheSharersPinStillMarksTheOthersAndSaysItWasLeftOut() = runTest {
        housekeeping.choices = { _, _ -> ReleaseChoice(true, true, SharedDelete.EVERYONE, 2, false) }
        housekeeping.protectedSharers = listOf(ann)
        val l = logic()
        val got = messages(l)
        finish(l)
        l.together(); runCurrent()
        l.cancelSharerPin(); runCurrent()
        assertNull(l.sharerPin.value)
        assertEquals(listOf("release:ben:$e1:true"), housekeeping.calls.filter { it.startsWith("prove") || it.startsWith("release") })
        assertEquals(listOf(WatchedMessage.LEFT_OUT), got)
    }

    /** Review I-1: Cancel or Back while a sharer's PIN is being checked is ignored; the late answer then lands on its own ask only. */
    @Test fun cancelWhileTheSharersPinIsCheckedIsIgnoredAndTheLateAnswerIsSafe() = runTest {
        housekeeping.choices = { _, _ -> ReleaseChoice(true, true, SharedDelete.EVERYONE, 1, false) }
        housekeeping.protectedSharers = listOf(ann)
        for (right in listOf(true, false)) {
            housekeeping.calls.clear()
            val gate = CompletableDeferred<Unit>().also { housekeeping.proveGate = it }
            val l = logic()
            finish(l)
            l.together(); runCurrent()
            l.pin.edit { replace(0, length, if (right) "4826" else "1111") }
            l.submitSharerPin(); runCurrent()
            l.cancelSharerPin(); runCurrent()
            assertEquals("still asking while busy", true, l.sharerPin.value?.busy)
            gate.complete(Unit); runCurrent()
            if (right) {
                assertNull(l.sharerPin.value)
                assertEquals(1, housekeeping.calls.count { it.startsWith("release") })
            } else {
                assertEquals(app.shoalwatch.security.PinCheck.Wrong(4), l.sharerPin.value?.answer)
                l.cancelSharerPin(); runCurrent()
                assertNull("the dialog can be closed", l.sharerPin.value)
                assertEquals(1, housekeeping.calls.count { it.startsWith("release") })
            }
        }
    }

    /** Review I-1: a profile change during the check drops the ask; the old answer never comes back for the new profile. */
    @Test fun aProfileChangeDuringTheCheckDropsTheAskForGood() = runTest {
        housekeeping.choices = { _, _ -> ReleaseChoice(true, true, SharedDelete.EVERYONE, 1, false) }
        housekeeping.protectedSharers = listOf(ann)
        val gate = CompletableDeferred<Unit>().also { housekeeping.proveGate = it }
        val l = logic()
        finish(l)
        l.together(); runCurrent()
        l.pin.edit { replace(0, length, "1111") }
        l.submitSharerPin(); runCurrent()
        l.onState(local.copy(session = AMY)); runCurrent()
        assertNull(l.sharerPin.value)
        gate.complete(Unit); runCurrent()
        assertNull("not brought back for AMY", l.sharerPin.value)
        assertEquals(0, housekeeping.calls.count { it.startsWith("release") })
    }

    /** Review M-1/M-2: each press starts with no proof left from an earlier one; the data layer's own "left out" reaches the snackbar. */
    @Test fun aPressDropsOldProofsAndTheDataLayersLeftOutIsSaid() = runTest {
        housekeeping.choices = { _, _ -> ReleaseChoice(true, true, SharedDelete.EVERYONE, 1, false) }
        housekeeping.leftOutByData = true
        val l = logic()
        val got = messages(l)
        finish(l)
        l.together(); runCurrent()
        assertEquals(listOf("drop:ben:$e1", "release:ben:$e1:true"), housekeeping.calls.filter { it.startsWith("drop") || it.startsWith("release") })
        assertEquals(listOf(WatchedMessage.LEFT_OUT), got)
    }

    @Test fun eachOutcomeGivesItsMessage() = runTest {
        val expected = mapOf(
            ReleaseOutcome.DELETED to WatchedMessage.DELETED,
            ReleaseOutcome.KEPT_FOR_OTHERS to WatchedMessage.KEPT_FOR_OTHERS,
            ReleaseOutcome.KEPT_UNTIL_EVERYONE_WATCHED to WatchedMessage.KEPT_UNTIL_EVERYONE,
            ReleaseOutcome.NOT_YOURS to WatchedMessage.FAILED,
        )
        for ((outcome, message) in expected) {
            housekeeping.outcome = outcome
            val l = logic()
            val got = messages(l)
            finish(l)
            l.delete(); runCurrent()
            assertEquals(outcome.name, listOf(message), got)
        }
    }

    @Test fun aFailureGivesFailed() = runTest {
        housekeeping.failWith = AppError.of(AppError.Kind.UNKNOWN)
        val l = logic()
        val got = messages(l)
        finish(l)
        l.delete(); runCurrent()
        assertEquals(listOf(WatchedMessage.FAILED), got)
    }

    @Test fun aPressWhileTheReleaseRunsIsNotRepeated() = runTest {
        housekeeping.gate = CompletableDeferred()
        val l = logic()
        finish(l)
        l.delete(); l.delete(); l.together(); runCurrent()
        housekeeping.gate?.complete(Unit); runCurrent()
        assertEquals(1, housekeeping.calls.count { it.startsWith("release") })
    }
}
