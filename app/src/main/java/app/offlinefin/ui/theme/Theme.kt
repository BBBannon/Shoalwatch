package app.offlinefin.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 with dynamic colour on Android 12+. Dark is the default look; a later settings
 * task can pass the user's choice (or `isSystemInDarkTheme()`) as [darkTheme].
 */
@Composable
fun OfflineFinTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colours = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColours
        else -> LightColours
    }
    MaterialTheme(colorScheme = colours, content = content)
}

private val DarkColours = darkColorScheme()
private val LightColours = lightColorScheme()
