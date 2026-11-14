package app.shoalwatch.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.shoalwatch.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** On a clean install the app opens on the Welcome / Add server screen (never on Home, never blank). */
@RunWith(AndroidJUnit4::class)
class WelcomeSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun cleanInstallShowsWelcomeAndAddServer() {
        val title = rule.activity.getString(R.string.add_title_first)
        val field = rule.activity.getString(R.string.add_address_label)
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(title).assertIsDisplayed()
        rule.onNodeWithText(field).assertIsDisplayed()
    }
}
