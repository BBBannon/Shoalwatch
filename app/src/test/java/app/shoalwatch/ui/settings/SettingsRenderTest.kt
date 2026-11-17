package app.shoalwatch.ui.settings

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.security.LockAfter
import app.shoalwatch.security.LockSettings
import app.shoalwatch.security.PinCheck
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.TABLET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** The Settings screen (Milestone 7 Task 7) on a phone and a tablet at font scale 1.0 and 2.0. */
abstract class SettingsRenderTest : RenderBase() {
    private val base = SettingsUiState(session = BEN, userName = "ben", deviceSecure = true)

    private fun screen(state: SettingsUiState, sdk: Int = 34, actions: SettingsActions = SettingsActions(onBack = {})) =
        show { SettingsScreen(state, actions, TextFieldState(), sdkInt = sdk) }

    /** The node for a row whose own text, or a child's, is [label] (a merged row). */
    private fun row(label: String): SemanticsNode = rule.onAllNodes(SemanticsMatcher("row $label") { n ->
        (n.config.getOrNull(SemanticsProperties.Text).orEmpty() + n.children.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() })
            .any { it.text == label } && (n.config.getOrNull(SemanticsProperties.ToggleableState) != null || n.config.getOrNull(SemanticsProperties.Selected) != null)
    }, useUnmergedTree = false).fetchSemanticsNodes().firstOrNull() ?: throw AssertionError("no row $label\n${tree()}")

    private fun enabled(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Disabled) == null

    private fun reach(label: String) {
        scrollTo(hasText(label))
        rule.onNodeWithText(label).assertIsDisplayed()
    }

    /** Every row and button in the list (the top bar's Back is the shared `AppScreen` one, 48dp by its touch target). */
    private fun assertTargets() {
        val min = 48.dp.value * context.resources.displayMetrics.density - 0.5f
        rule.onAllNodes(hasClickAction().and(hasAnyAncestor(hasScrollAction()))).fetchSemanticsNodes().forEach { assertTrue("under 48dp: ${it.config}", it.size.height >= min) }
    }

    @Test fun everyRowAndHelpLineOfAnUnprotectedProfile() {
        screen(base)
        assertShown(text(R.string.settings_title))
        listOf(
            text(R.string.settings_playback), text(R.string.settings_autoplay), text(R.string.settings_autoplay_help),
            text(R.string.settings_profile, "ben"), text(R.string.settings_protect), text(R.string.settings_protect_help),
            text(R.string.settings_app_lock_heading), text(R.string.settings_app_lock), text(R.string.settings_lock_after),
            text(R.string.lock_after_immediately), text(R.string.lock_after_1), text(R.string.lock_after_5), text(R.string.lock_after_15),
            text(R.string.settings_lock_after_help), text(R.string.settings_privacy), text(R.string.settings_hide_recents),
            text(R.string.settings_downloads),
        ).forEach { reach(it) }
        assertEquals("Play the next episode automatically", text(R.string.settings_autoplay))
        assertEquals("Anyone using this phone will need the PIN to switch to this profile. Use 4 to 12 digits.", text(R.string.settings_protect_help))
        assertFalse("no screen lock help while the phone has one", shown(text(R.string.settings_app_lock_no_screen_lock)))
        assertFalse("not Android 10–12", shown(text(R.string.settings_hide_recents_old_android)))
        assertEquals(ToggleableState.On, row(text(R.string.settings_autoplay)).config[SemanticsProperties.ToggleableState])
        assertTargets()
    }

    @Test fun aProtectedProfileOffersChangeAndRemove() {
        screen(base.copy(profileProtected = true, autoplay = false))
        reach(text(R.string.settings_protected))
        reach(text(R.string.settings_change_pin))
        reach(text(R.string.settings_remove_pin))
        assertFalse(shown(text(R.string.settings_protect)))
        assertEquals(ToggleableState.Off, row(text(R.string.settings_autoplay)).config[SemanticsProperties.ToggleableState])
    }

    @Test fun withoutAScreenLockTheAppLockRowIsOffDisabledAndSaysWhy() {
        screen(base.copy(deviceSecure = false, lock = LockSettings(appLock = true)))
        reach(text(R.string.settings_app_lock_no_screen_lock))
        val r = row(text(R.string.settings_app_lock))
        assertFalse("disabled", enabled(r))
        assertEquals(ToggleableState.Off, r.config[SemanticsProperties.ToggleableState])
    }

    @Test fun forcedRecentsShowOnAndDisabledWithTheirHelp() {
        screen(base.copy(recentsForced = true))
        reach(text(R.string.settings_hide_recents_forced))
        val r = row(text(R.string.settings_hide_recents))
        assertFalse(enabled(r))
        assertEquals(ToggleableState.On, r.config[SemanticsProperties.ToggleableState])
    }

    @Test fun onAndroid10To12TheHelpSaysScreenshotsStopToo() {
        screen(base.copy(recentsForced = true), sdk = 31)
        reach(text(R.string.settings_hide_recents_forced) + " " + text(R.string.settings_hide_recents_old_android))
    }

    @Test fun theLockAfterRadiosSayWhichIsSelected() {
        screen(base.copy(lock = LockSettings(lockAfter = LockAfter.FIVE_MINUTES)))
        scrollTo(hasText(text(R.string.lock_after_15)))
        assertEquals(true, row(text(R.string.lock_after_5)).config.getOrNull(SemanticsProperties.Selected))
        assertEquals(false, row(text(R.string.lock_after_1)).config.getOrNull(SemanticsProperties.Selected))
        assertTargets()
    }

    @Test fun nobodySignedInShowsOnlyThePhonesSettings() {
        screen(SettingsUiState(deviceSecure = true))
        assertFalse(shown(text(R.string.settings_autoplay)))
        assertFalse(shown(text(R.string.settings_protect)))
        reach(text(R.string.settings_app_lock))
    }

    @Test fun eachPinStepHasItsTitleAndNotes() {
        val steps = listOf(
            base.copy(pinStep = PinStep.NEW, pinMismatch = true) to listOf(R.string.settings_pin_choose, R.string.settings_pin_mismatch),
        )
        steps.forEach { (state, words) ->
            screen(state)
            words.forEach { assertShown(text(it)) }
            assertNotNull(rule.onAllNodes(isDialog()).fetchSemanticsNodes().firstOrNull())
        }
        assertEquals("Those PINs don’t match. Try again.", text(R.string.settings_pin_mismatch))
        assertEquals("Enter the PIN again", text(R.string.settings_pin_repeat))
        assertEquals("Enter the current PIN", text(R.string.settings_pin_current))
    }

    @Test fun aWrongCurrentPinSaysSo() {
        screen(base.copy(profileProtected = true, pinStep = PinStep.CURRENT_FOR_REMOVE, pinAnswer = PinCheck.Wrong(3)))
        assertShown(text(R.string.settings_pin_current))
        assertTrue(shown(context.resources.getQuantityString(R.plurals.pin_wrong, 3, 3)))
    }

    @Test fun theRaceLineShows() {
        screen(base.copy(householdRace = true))
        assertShown(text(R.string.grownup_race))
    }

    @Test fun tappingARowPassesTheWish() {
        var wish: Boolean? = null
        var after: LockAfter? = null
        screen(base, actions = SettingsActions(onBack = {}, onAppLock = { wish = it }, onLockAfter = { after = it }))
        scrollTo(hasText(text(R.string.settings_app_lock)))
        rule.onNodeWithText(text(R.string.settings_app_lock)).performClick()
        scrollTo(hasText(text(R.string.lock_after_15)))
        rule.onNodeWithText(text(R.string.lock_after_15)).performClick()
        assertEquals(true, wish)
        assertEquals(LockAfter.FIFTEEN_MINUTES, after)
    }
}

@Config(qualifiers = PHONE) class SettingsPhoneRenderTest : SettingsRenderTest() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class SettingsPhoneLargeTextRenderTest : SettingsRenderTest() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class SettingsTabletRenderTest : SettingsRenderTest() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class SettingsTabletLargeTextRenderTest : SettingsRenderTest() { override val fontScale = 2f }
