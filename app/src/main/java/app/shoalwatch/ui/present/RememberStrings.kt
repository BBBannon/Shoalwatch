package app.shoalwatch.ui.present

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** The [Strings] for the current language: made again when the configuration (language, size) changes. */
@Composable
fun rememberStrings(): Strings {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { Strings(context.resources) }
}
