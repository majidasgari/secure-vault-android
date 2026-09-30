package ir.maxv.securevault.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF12161C)
private val InkSurface = Color(0xFF1B2029)
private val InkSurfaceHigh = Color(0xFF232A35)
private val Blue = Color(0xFF8AB4F8)
private val Violet = Color(0xFFC8A2FF)
private val Mint = Color(0xFF8BD5B0)
private val Danger = Color(0xFFFF8A8A)

private val DarkColors = darkColorScheme(
    primary = Blue,
    onPrimary = Color(0xFF0B1220),
    secondary = Violet,
    onSecondary = Color(0xFF161021),
    tertiary = Mint,
    background = Ink,
    onBackground = Color(0xFFE6EAF2),
    surface = InkSurface,
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = InkSurfaceHigh,
    onSurfaceVariant = Color(0xFFB6BFCC),
    outline = Color(0xFF3A4350),
    error = Danger,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2C5FA8),
    secondary = Color(0xFF6A46B8),
    tertiary = Color(0xFF1E7A55),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EDF3),
    error = Color(0xFFB3261E),
)

private val AppTypography = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontSize = 14.5.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 21.sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 26.sp),
    labelLarge = TextStyle(fontSize = 14.sp),
)

val MonoFamily: FontFamily = FontFamily.Monospace

@Composable
fun SecureVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
