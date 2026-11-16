package app.shoalwatch.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A message on screen belongs to the session that caused it: when the session changes it goes at once. */
@RunWith(RobolectricTestRunner::class)
class CollectMessagesTest {
    @get:Rule val rule = createComposeRule()

    private fun count(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size

    /** How much (test) time passes before [text] has left the screen after [change]; a snackbar times out by itself after 4 s. */
    private fun timeToLeave(text: String, change: () -> Unit): Long {
        rule.waitUntil(5_000) { count(text) == 1 }
        val before = rule.mainClock.currentTime
        change()
        rule.waitUntil(10_000) { count(text) == 0 }
        return rule.mainClock.currentTime - before
    }

    @Test fun aMessageShowingForOneSessionIsDismissedWhenTheSessionChanges() {
        val snackbar = SnackbarHostState()
        var session by mutableStateOf<Session?>(BEN)
        val messages = flowOf("Couldn't mark as watched") // one flow, as a ViewModel's is, not a new one on every recomposition
        rule.setContent {
            CollectMessages(messages, snackbar, session)
            SnackbarHost(snackbar)
        }
        val took = timeToLeave("Couldn't mark as watched") { session = AMY }
        assertTrue("took $took ms: it timed out instead of being dismissed", took < 2_000)
    }

    @Test fun aMessageStaysWhileTheSessionDoesNotChange() {
        val snackbar = SnackbarHostState()
        rule.setContent {
            CollectMessages(flowOf("Couldn't mark as watched"), snackbar, BEN)
            SnackbarHost(snackbar)
        }
        rule.waitUntil(5_000) { count("Couldn't mark as watched") == 1 }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(1, count("Couldn't mark as watched"))
    }

    @Test fun dismissOnSessionChangeAloneClearsAnotherHostsMessage() {
        val snackbar = SnackbarHostState()
        var session by mutableStateOf<Session?>(BEN)
        rule.setContent {
            DismissOnSessionChange(snackbar, session)
            SnackbarHost(snackbar)
            androidx.compose.runtime.LaunchedEffect(Unit) { launch { snackbar.showSnackbar("From elsewhere", duration = SnackbarDuration.Short) } }
        }
        val took = timeToLeave("From elsewhere") { session = AMY }
        assertTrue("took $took ms: it timed out instead of being dismissed", took < 2_000)
    }
}
