package com.hermes.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Palette modelled on Feishu / QQ: a calm blue accent on a near-white
 * "paper" background in light mode, and a deep neutral in dark mode so the
 * app sits naturally next to HyperOS system surfaces.
 */
private val HermesBlue = Color(0xFF0F5BFF)
private val HermesBlueDark = Color(0xFF3D7BFF)

private val LightColors = lightColorScheme(
    primary = HermesBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6EEFF),
    onPrimaryContainer = Color(0xFF00184A),
    secondary = Color(0xFF12B76A),
    onSecondary = Color.White,
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF17181A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17181A),
    surfaceVariant = Color(0xFFEFF1F5),
    onSurfaceVariant = Color(0xFF5B6169),
    outline = Color(0xFFD5D9E0),
    outlineVariant = Color(0xFFE6E9EF),
    error = Color(0xFFE5484D),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = HermesBlueDark,
    onPrimary = Color(0xFF00184A),
    primaryContainer = Color(0xFF10306E),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFF3DD68C),
    onSecondary = Color(0xFF00391C),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE7E9EE),
    surface = Color(0xFF161A21),
    onSurface = Color(0xFFE7E9EE),
    surfaceVariant = Color(0xFF222730),
    onSurfaceVariant = Color(0xFFA6ADBA),
    outline = Color(0xFF333A45),
    outlineVariant = Color(0xFF262C35),
    error = Color(0xFFFF6369),
    onError = Color(0xFF3A0A0C),
)

private val HermesTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun HermesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            val activity = context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = HermesTypography,
        content = content,
    )
}