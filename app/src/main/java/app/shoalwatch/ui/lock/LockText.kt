package app.shoalwatch.ui.lock

import androidx.compose.runtime.Composable
import app.shoalwatch.R
import app.shoalwatch.security.PinCheck
import app.shoalwatch.security.PinThrottle
import app.shoalwatch.ui.present.Strings
import app.shoalwatch.ui.present.rememberStrings
import app.shoalwatch.ui.present.waitWords

/**
 * The line shown under a PIN field for the last answer, or null. [failed]: the try could not be saved, so nothing was checked
 * (`ProfileLocks.check` threw `AppError`): never shown as right. `Wrong(0)` means the wait has already started, so it says how long.
 */
internal fun pinAnswerText(strings: Strings, answer: PinCheck?, failed: Boolean): String? = when {
    failed -> strings.get(R.string.pin_not_saved)
    answer is PinCheck.Wrong && answer.triesLeft > 0 -> strings.pluralNumber(R.plurals.pin_wrong, answer.triesLeft)
    answer is PinCheck.Wrong -> strings.get(R.string.pin_wrong_last, waitWords(strings, (PinThrottle.waitMs(PinThrottle.FREE_TRIES) / 1000).toInt()))
    answer is PinCheck.Wait -> strings.get(R.string.pin_wait, waitWords(strings, answer.seconds))
    answer == PinCheck.Unreadable -> strings.get(R.string.pin_unreadable)
    else -> null
}

@Composable
internal fun pinAnswerText(answer: PinCheck?, failed: Boolean): String? = pinAnswerText(rememberStrings(), answer, failed)

/** A copy of [chars] as an array, never a `String`. */
internal fun CharSequence.toPinArray(): CharArray = CharArray(length) { this[it] }
