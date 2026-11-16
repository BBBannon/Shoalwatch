package app.shoalwatch.ui.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.data.downloads.DownloadPlan
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadTarget
import app.shoalwatch.data.downloads.PlannedItem
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FakeDownloads
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one place screens prepare and start downloads: its steps, the default quality, session changes, failures, space, double taps. */
class DownloadActionsTest {
    private val downloads = FakeDownloads()
    private var current: Session? = BEN
    private val target = DownloadTarget.Item("1".repeat(32))

    private fun plan(s: Session, enough: Boolean = true, n: Int = 2) = DownloadPlan(
        s, DownloadQuality.ORIGINAL, List(n) { PlannedItem("$it".repeat(32), DownloadQuality.ORIGINAL, 1_000, false) }, emptyList(),
        0, 2_000, 10_000, enough, StorageKind.INTERNAL, null, mobileData = false,
    )

    @Test fun askChooseConfirmStartsThePlanAsItsSession() = runTest(UnconfinedTestDispatcher()) {
        downloads.plans = { s, _, _ -> plan(s) }
        val a = DownloadActions(backgroundScope, downloads) { current }
        a.ask(BEN, target, film = true)
        assertEquals(DownloadSheetState.Quality(BEN, target), a.sheet.value)
        a.choose(DownloadQuality.ORIGINAL)
        val confirm = a.sheet.value as DownloadSheetState.Confirm
        assertEquals(BEN, confirm.plan.session)
        assertTrue(confirm.films)
        a.confirm()
        assertEquals(listOf("prepare", "start:2"), downloads.calls)
        assertNull(a.sheet.value)
    }

    @Test fun whilePreparingItSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val slow = object : app.shoalwatch.data.downloads.Downloads by downloads {
            override suspend fun prepare(s: Session, target: DownloadTarget, quality: DownloadQuality): DownloadPlan { gate.await(); return plan(s) }
        }
        val a = DownloadActions(backgroundScope, slow) { current }
        a.ask(BEN, target); a.choose(DownloadQuality.HIGH)
        assertEquals(DownloadSheetState.Preparing(BEN), a.sheet.value)
        gate.complete(Unit)
        assertTrue(a.sheet.value is DownloadSheetState.Confirm)
    }

    @Test fun aDefaultQualitySkipsTheQualityStepAndUseThisEveryTimeSetsIt() = runTest(UnconfinedTestDispatcher()) {
        downloads.plans = { s, _, q -> plan(s).copy(requested = q) }
        val a = DownloadActions(backgroundScope, downloads) { current }
        a.ask(BEN, target)
        a.choose(DownloadQuality.MEDIUM, remember = true)
        assertEquals(DownloadQuality.MEDIUM, downloads.settings.value.defaultQuality)
        a.dismiss()
        a.ask(BEN, target)
        assertEquals("straight to confirm", DownloadQuality.MEDIUM, (a.sheet.value as DownloadSheetState.Confirm).plan.requested)
    }

    @Test fun aSwitchOfAccountWhilePreparingDropsTheSheetAndItsMessage() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val slow = object : app.shoalwatch.data.downloads.Downloads by downloads {
            override suspend fun prepare(s: Session, target: DownloadTarget, quality: DownloadQuality): DownloadPlan { gate.await(); throw AppError.of(AppError.Kind.OFFLINE) }
        }
        val a = DownloadActions(backgroundScope, slow) { current }
        a.ask(BEN, target); a.choose(DownloadQuality.ORIGINAL)
        current = AMY
        gate.complete(Unit)
        assertNull("nothing of Ben's is shown to Amy", a.sheet.value)
    }

    @Test fun anAppErrorGivesFailedWithItsOwnWords() = runTest(UnconfinedTestDispatcher()) {
        downloads.prepareFailure = AppError.of(AppError.Kind.OFFLINE)
        val a = DownloadActions(backgroundScope, downloads) { current }
        a.ask(BEN, target); a.choose(DownloadQuality.ORIGINAL)
        assertEquals(DownloadSheetState.Failed(BEN, AppError.of(AppError.Kind.OFFLINE).userMessage), a.sheet.value)
    }

    @Test fun confirmWithoutEnoughSpaceDoesNothing() = runTest(UnconfinedTestDispatcher()) {
        downloads.plans = { s, _, _ -> plan(s, enough = false) }
        val a = DownloadActions(backgroundScope, downloads) { current }
        a.ask(BEN, target); a.choose(DownloadQuality.ORIGINAL)
        a.confirm()
        assertEquals(listOf("prepare"), downloads.calls)
        assertTrue(a.sheet.value is DownloadSheetState.Confirm)
    }

    @Test fun aDoubleConfirmStartsOnce() = runTest(UnconfinedTestDispatcher()) {
        downloads.plans = { s, _, _ -> plan(s) }
        val a = DownloadActions(backgroundScope, downloads) { current }
        a.ask(BEN, target); a.choose(DownloadQuality.ORIGINAL)
        a.confirm(); a.confirm()
        assertEquals(1, downloads.calls.count { it.startsWith("start") })
    }

    @Test fun aSessionChangeTakesTheSheetDown() = runTest(UnconfinedTestDispatcher()) {
        val a = DownloadActions(backgroundScope, downloads) { current }
        a.ask(BEN, target)
        a.sessionChanged(AMY)
        assertNull(a.sheet.value)
    }
}
