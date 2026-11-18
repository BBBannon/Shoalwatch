package app.shoalwatch.ui.lock

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.R
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.Judged
import app.shoalwatch.ui.components.ConfirmDialog
import app.shoalwatch.ui.components.ItemActionsSheet
import app.shoalwatch.ui.components.LocalCovered
import app.shoalwatch.ui.components.SheetActions
import app.shoalwatch.ui.components.SheetTarget
import app.shoalwatch.ui.downloads.DeleteMenu
import app.shoalwatch.ui.nav.LockCover
import app.shoalwatch.ui.theme.ShoalwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Tasks 5-6 review M1: a dialog, sheet or menu left open by a profile is its own window, so the in-window cover cannot hide it. While
 * covered, none of them is composed: on the very frame the cover appears they are gone, and TalkBack has nothing of them to announce.
 */
@RunWith(RobolectricTestRunner::class)
class CoveredWindowsTest {
    @get:Rule val rule = createComposeRule()

    private val adult = Judged(Session("s1", "u1"))
    private val target = SheetTarget("i1", "SHEET TITLE", played = false, favourite = false, canRemoveFromResume = false, seriesId = null)
    private val sheetActions = SheetActions({}, { _, _ -> }, { _, _ -> }, {}, {})

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val menuItem get() = context.getString(R.string.dl_delete)

    private fun count(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test fun windowsLeftOpenAreGoneOnTheFrameTheCoverAppears() {
        var openFor by mutableStateOf<Judged?>(adult)
        rule.setContent {
            ShoalwatchTheme {
                LockCover(openFor, adult, host = {
                    Column {
                        ConfirmDialog("DIALOG TITLE", "Remove", {}, {}) { Text("body") }
                        ItemActionsSheet(target, sheetActions) {}
                        DeleteMenu("Film", {})
                    }
                }, cover = { Text("COVER") })
            }
        }
        rule.onNodeWithContentDescription(context.getString(R.string.cd_more_actions, "Film")).performClick()
        rule.waitForIdle()
        assertEquals(1, count("DIALOG TITLE"))
        assertEquals(1, count("SHEET TITLE"))
        assertEquals(1, count(menuItem))
        rule.mainClock.autoAdvance = false
        openFor = null // the gate has locked
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(1, count("COVER"))
        assertEquals(0, count("DIALOG TITLE"))
        assertEquals(0, count("SHEET TITLE"))
        assertEquals(0, count(menuItem))
        assertTrue(rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        rule.mainClock.autoAdvance = true
    }

    @Test fun theCoversOwnPinDialogStillShows() {
        rule.setContent {
            ShoalwatchTheme {
                LockCover(null, adult, host = {}, cover = {
                    PinDialog("CHOOSE", "body", androidx.compose.foundation.text.input.TextFieldState(), null, "Switch", {}, {})
                })
            }
        }
        rule.waitForIdle()
        assertEquals(1, count("CHOOSE"))
    }

    /** Every file that opens a separate window (dialog, sheet, menu) reads [LocalCovered], so a new one cannot forget. */
    @Test fun everyWindowOfTheAppReadsTheCover() {
        val root = File("src/main/java/app/shoalwatch")
        val opens = Regex("""\b(AlertDialog|BasicAlertDialog|Dialog|ModalBottomSheet|DropdownMenu|Popup|DatePickerDialog)\(""")
        val missing = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { opens.containsMatchIn(it.readText()) && !it.readText().contains("LocalCovered.current") }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        assertEquals("open a window without reading LocalCovered", emptyList<String>(), missing)
    }
}
