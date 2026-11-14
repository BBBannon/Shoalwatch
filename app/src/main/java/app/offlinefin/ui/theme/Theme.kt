package app.offlinefin.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * One fixed brand scheme (deep-sea teal with a warm coral accent), dark by default. Dynamic colour is
 * deliberately not used: a consistent identity matters more than matching the wallpaper. Contrast (WCAG):
 * body text at least 4.5:1 and large text, icons and borders at least 3:1 in both schemes (ratios are listed
 * in the task report).
 */
@Composable
fun OfflineFinTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColours else LightColours,
        typography = AppTypography,
        content = content,
    )
}

internal val DarkColours = darkColorScheme(
    primary = Color(0xFF4FD1C5), onPrimary = Color(0xFF00201D),
    primaryContainer = Color(0xFF00504A), onPrimaryContainer = Color(0xFF9EF2E9),
    secondary = Color(0xFFA9CCC7), onSecondary = Color(0xFF0F3733),
    secondaryContainer = Color(0xFF2B4B47), onSecondaryContainer = Color(0xFFC5E8E3),
    tertiary = Color(0xFFFF9E8A), onTertiary = Color(0xFF4A0F05),
    tertiaryContainer = Color(0xFF6A2B1E), onTertiaryContainer = Color(0xFFFFDAD2),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1716), onBackground = Color(0xFFDDE5E3),
    surface = Color(0xFF0F1716), onSurface = Color(0xFFDDE5E3),
    surfaceVariant = Color(0xFF3F4947), onSurfaceVariant = Color(0xFFBCC9C6),
    surfaceContainerLowest = Color(0xFF0A1110), surfaceContainerLow = Color(0xFF121B1A),
    surfaceContainer = Color(0xFF161F1E), surfaceContainerHigh = Color(0xFF1C2625), surfaceContainerHighest = Color(0xFF232E2D),
    outline = Color(0xFF8A9996), outlineVariant = Color(0xFF3F4947),
)

internal val LightColours = lightColorScheme(
    primary = Color(0xFF00695C), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9FF2E5), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A6360), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E3), onSecondaryContainer = Color(0xFF05201D),
    tertiary = Color(0xFFB3402A), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD2), onTertiaryContainer = Color(0xFF3D0A02),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FAF9), onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF6FAF9), onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE5E2), onSurfaceVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF0F5F4),
    surfaceContainer = Color(0xFFEAEFEE), surfaceContainerHigh = Color(0xFFE4E9E8), surfaceContainerHighest = Color(0xFFDEE4E2),
    outline = Color(0xFF6F7977), outlineVariant = Color(0xFFBEC9C6),
)

/**
 * Material 3 defaults with firmer headings and a larger, airier body for comfortable reading (body 18/16/14sp
 * with generous line height, labels 16sp). Headings keep their sizes.
 */
private val AppTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = base.labelLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    )
}

/** Large monospace for things people compare or type by eye: Quick Connect codes and certificate fingerprints. */
object CodeStyles {
    val Code = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = 6.sp)
    val Fingerprint = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp, lineHeight = 28.sp, letterSpacing = 1.sp)
}
