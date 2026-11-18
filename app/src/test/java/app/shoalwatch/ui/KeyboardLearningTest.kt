package app.shoalwatch.ui

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import app.shoalwatch.ui.onboarding.AddressField
import app.shoalwatch.ui.onboarding.PasswordPanel
import app.shoalwatch.ui.onboarding.SignInActions
import app.shoalwatch.ui.onboarding.SignInMode
import app.shoalwatch.ui.onboarding.SignInUiState
import app.shoalwatch.ui.search.SearchActions
import app.shoalwatch.ui.search.SearchScreen
import app.shoalwatch.ui.search.SearchUiState
import app.shoalwatch.ui.theme.ShoalwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Security review M8 F4: on a shared phone the keyboard must not learn what one profile searched for, the household's user names,
 * the server address or a custom header (they would come back as suggestions for anyone, and some keyboards sync them). The
 * PIN fields already asked this (`NoAutofillTest`); now every text field does, and a source rule keeps it so.
 */
@RunWith(RobolectricTestRunner::class)
class KeyboardLearningTest {
    @get:Rule val rule = createComposeRule()

    private fun imeOptionsOfField(index: Int = 0, content: @Composable () -> Unit): Int {
        var view: View? = null
        rule.setContent {
            view = LocalView.current
            ShoalwatchTheme { content() }
        }
        rule.onAllNodes(hasSetTextAction())[index].performClick()
        rule.waitForIdle()
        val info = EditorInfo()
        var connection: Any? = null
        rule.runOnIdle { connection = view!!.onCreateInputConnection(info) }
        assertNotNull("a text input session started", connection)
        return info.imeOptions
    }

    private fun assertNoLearning(options: Int) = assertTrue("no personalised learning", options and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)

    @Test fun theSearchBoxAsksTheKeyboardNotToLearn() {
        val actions = SearchActions(onQueryChange = {}, onClear = {}, onRetry = {}, onOpen = {}, onOpenServers = {})
        assertNoLearning(imeOptionsOfField { SearchScreen(SearchUiState(), actions) })
    }

    @Test fun theSignInUserNameAsksTheKeyboardNotToLearn() {
        assertNoLearning(imeOptionsOfField { PasswordPanel(SignInUiState(mode = SignInMode.Password), TextFieldState(), SignInActions({}, {}, {}, {}, {}, {})) })
    }

    @Test fun theServerAddressAsksTheKeyboardNotToLearn() {
        assertNoLearning(imeOptionsOfField { AddressField("", {}, "Server address", isError = false, enabled = true, onGo = {}) })
    }

    /** Every text field under `ui/` (dialogs included, whose windows the tests above cannot reach) sits inside `NoKeyboardLearning { }`. */
    @Test fun everyTextFieldIsInsideNoKeyboardLearning() {
        val root = File("src/main/java/app/shoalwatch/ui")
        val call = Regex("""(?<![\w.])(OutlinedTextField|OutlinedSecureTextField|TextField|BasicTextField|BasicSecureTextField|SecureTextField)\(""")
        val outside = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val text = file.readText()
            call.findAll(text).filterNot { inside(text, it.range.first) }.map { "${file.relativeTo(root).invariantSeparatorsPath}: ${it.value}" }
        }.toList()
        assertEquals(emptyList<String>(), outside)
    }

    /** True when an open `NoKeyboardLearning {` block encloses [at] (braces counted from the nearest opening before it). */
    private fun inside(text: String, at: Int): Boolean {
        var from = text.lastIndexOf("NoKeyboardLearning {", at)
        while (from >= 0) {
            val between = text.substring(from + "NoKeyboardLearning ".length, at)
            if (between.count { it == '{' } > between.count { it == '}' }) return true
            from = text.lastIndexOf("NoKeyboardLearning {", from - 1)
        }
        return false
    }
}
