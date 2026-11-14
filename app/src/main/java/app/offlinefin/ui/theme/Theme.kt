package app.offlinefin.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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
    MaterialTheme(colorScheme = colours, typography = AppTypography, content = content)
}

private val DarkColours = darkColorScheme()
private val LightColours = lightColorScheme()

/** The Material 3 defaults with firmer headings, so screen titles read clearly at any font scale. */
private val AppTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    )
}

/** Large monospace for things people compare or type by eye: Quick Connect codes and certificate fingerprints. */
object CodeStyles {
    val Code = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = 6.sp)
    val Fingerprint = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp, lineHeight = 28.sp, letterSpacing = 1.sp)
}
