package app.shoalwatch.ui.lock

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.Judged
import app.shoalwatch.security.LockState
import app.shoalwatch.security.PinCheck
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.TABLET
import app.shoalwatch.ui.nav.LockCover
import app.shoalwatch.ui.present.Strings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** The lock screen's words, rows, targets and cover, on a phone and a tablet at font scale 1.0 and 2.0. */
abstract class LockScreenRenderTest : RenderBase() {
    private val adult = Session("s1", "u1")
    private val actions = LockActions({}, {}, {}, {}, {}, {}, {}, {})
    private val others = listOf(
        OtherProfile("s1", "u2", "Kid", "Test Jellyfin", isProtected = false),
        OtherProfile("s1", "u3", "Anna", "Test Jellyfin", isProtected = true),
    )
    private val profile = LockUiState(lock = LockState.Profile(adult, "Ben"), others = others, lockedServerName = "Test Jellyfin")

    private fun lockScreen(state: LockUiState, unavailable: Boolean = false) =
        show { LockScreen(state, TextFieldState(), TextFieldState(), TextFieldState(), actions, unavailable) }

    private fun assertTargets() {
        val min = 48.dp.value * context.resources.displayMetrics.density - 0.5f
        rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
            assertTrue("a target under 48dp: ${node.config}\n${tree()}", node.size.height >= min)
        }
    }

    @Test fun theAppLock() {
        lockScreen(LockUiState(lock = LockState.App))
        assertShown(text(R.string.lock_app_title))
        assertShown(text(R.string.lock_app_body))
        assertShown(text(R.string.lock_unlock))
        assertTargets()
    }

    @Test fun theAppLockWhenTheScreenLockCannotBeUsed() {
        lockScreen(LockUiState(lock = LockState.App), unavailable = true)
        assertShown(text(R.string.lock_unavailable))
    }

    @Test fun theProfileLockWithOtherProfiles() {
        lockScreen(profile)
        assertShown(text(R.string.lock_profile_title, "Ben"))
        assertShown(text(R.string.lock_profile_body))
        assertShown(text(R.string.pin_label))
        assertShown(text(R.string.lock_unlock))
        assertShown(text(R.string.lock_forgot))
        assertShown(text(R.string.lock_other_heading))
        assertDescribed("Kid on Test Jellyfin")
        assertDescribed("Anna on Test Jellyfin, protected")
        assertTrue(rule.onAllNodesWithText(text(R.string.lock_protected), useUnmergedTree = true).fetchSemanticsNodes().size == 1)
        assertTargets()
    }

    @Test fun everyPinAnswer() {
        val strings = Strings(context.resources)
        val answers = listOf(PinCheck.Wrong(3), PinCheck.Wrong(1), PinCheck.Wrong(0), PinCheck.Wait(90), PinCheck.Wait(30), PinCheck.Unreadable)
        val state = MutableStateFlow(profile)
        show {
            val s by state.collectAsState()
            LockScreen(s, TextFieldState(), TextFieldState(), TextFieldState(), actions)
        }
        answers.forEach { a ->
            state.value = profile.copy(pinAnswer = a)
            rule.waitForIdle()
            assertShown(pinAnswerText(strings, a, false)!!)
        }
        state.value = profile.copy(pinFailed = true)
        rule.waitForIdle()
        assertShown(text(R.string.pin_not_saved))
        assertShown(text(R.string.lock_forgot)) // "Forgot PIN" still offered
    }

    @Test fun theWordsForEachAnswer() {
        val strings = Strings(context.resources)
        assertTrue(pinAnswerText(strings, PinCheck.Wrong(0), false)!!.endsWith("Try again in 30 seconds."))
        assertTrue(pinAnswerText(strings, PinCheck.Wait(90), false)!!.endsWith("Try again in 1 minute 30 seconds."))
        assertTrue(pinAnswerText(strings, PinCheck.Wait(120), false)!!.endsWith("Try again in 2 minutes."))
        assertTrue(pinAnswerText(strings, PinCheck.Wrong(1), false)!!.contains("1 try left"))
        assertTrue(pinAnswerText(strings, PinCheck.Correct, false) == null)
    }

    @Test fun theForgotPanel() {
        lockScreen(profile.copy(forgot = true))
        assertShown(text(R.string.lock_forgot_body, "Test Jellyfin"))
        assertShown(text(R.string.password_label))
        assertShown(text(R.string.lock_forgot_submit))
        assertShown(text(R.string.action_cancel))
        assertNotShown(text(R.string.lock_other_heading))
        assertTargets()
    }

    @Test fun theForgotPanelsErrors() {
        lockScreen(profile.copy(forgot = true, forgotOtherAccount = true))
        assertShown(text(R.string.lock_forgot_other_account))
    }

    @Test fun aFailedReadIsAPlainError() {
        lockScreen(profile.copy(readFailed = true))
        assertShown(text(R.string.lock_read_failed))
        assertShown(text(R.string.lock_profile_title, "Ben"))
    }

    @Test fun theForgotPanelSaysHowLongToWait() {
        lockScreen(profile.copy(forgot = true, forgotWait = 30, forgotError = app.shoalwatch.core.AppError.Kind.UNAUTHORISED))
        assertShown(text(R.string.lock_forgot_wait, app.shoalwatch.ui.present.waitWords(Strings(context.resources), 30)))
    }

    @Test fun theChosenProfilesPinDialog() {
        lockScreen(profile.copy(choosing = others[1], pinAnswer = PinCheck.Wrong(4)))
        assertShown(text(R.string.switch_pin_title, "Anna"))
        assertShown(text(R.string.switch_pin_body, "Anna"))
        assertShown(text(R.string.switch_pin_confirm))
    }

    @Test fun nothingOfTheHostIsInTheTreeWhileCovered() {
        val openFor = MutableStateFlow<Judged?>(null)
        show {
            val o by openFor.collectAsState()
            LockCover(o, Judged(adult), host = { Text("SECRET") }, cover = { LockScreen(profile, TextFieldState(), TextFieldState(), TextFieldState(), actions) })
        }
        assertTrue(rule.onAllNodesWithText("SECRET").fetchSemanticsNodes().isEmpty())
        assertShown(text(R.string.lock_profile_title, "Ben"))
        openFor.value = Judged(adult)
        rule.waitForIdle()
        assertShown("SECRET")
        assertNotShown(text(R.string.lock_profile_title, "Ben"))
    }

    /**
     * Review I3: the screens see a newly active protected profile before the gate has judged it. From the very first frame with
     * that profile active, the cover is there and nothing of the profile is in the tree.
     */
    @Test fun aNewlyActiveProfileIsCoveredFromTheFirstFrame() {
        val kid = Session("s1", "u2")
        val active = MutableStateFlow(Judged(kid))
        val openFor = MutableStateFlow<Judged?>(Judged(kid))
        show {
            val a by active.collectAsState()
            val o by openFor.collectAsState()
            LockCover(o, a, host = { Text(if (a.session == adult) "ADULT CONTENT" else "kid content") }, cover = { Text("COVER") })
        }
        assertShown("kid content")
        rule.mainClock.autoAdvance = false
        active.value = Judged(adult) // the store has switched; the gate has not judged yet
        rule.mainClock.advanceTimeByFrame()
        assertTrue(rule.onAllNodesWithText("ADULT CONTENT").fetchSemanticsNodes().isEmpty())
        assertShown("COVER")
        rule.mainClock.autoAdvance = true
    }
}

@Config(qualifiers = PHONE) class LockScreenPhoneRenderTest : LockScreenRenderTest() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class LockScreenPhoneLargeTextRenderTest : LockScreenRenderTest() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class LockScreenTabletRenderTest : LockScreenRenderTest() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class LockScreenTabletLargeTextRenderTest : LockScreenRenderTest() { override val fontScale = 2f }
