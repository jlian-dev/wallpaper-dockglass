package com.dockliquida.app.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/*
 * Theme.kt — cores (Material You), formas arredondadas e tipografia mais "editorial".
 * `mode` é "auto", "claro" ou "escuro".
 */
private val base = Typography()
private val AppTypography = base.copy(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold)
)
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(36.dp)
)

// Paleta fixa neutra quando Material You estiver desligado. Sem cores roxas.
private val DockLightColors = lightColorScheme(
    primary = Color(0xFF484848), onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E5E5), onPrimaryContainer = Color(0xFF171717),
    secondary = Color(0xFF626262), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8E8), onSecondaryContainer = Color(0xFF222222),
    tertiary = Color(0xFF555555), onTertiary = Color.White,
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF171717),
    surface = Color(0xFFF5F5F5), onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFE5E5E5), onSurfaceVariant = Color(0xFF444444),
    outline = Color(0xFF777777)
)

private val DockDarkColors = darkColorScheme(
    primary = Color(0xFFE0E0E0), onPrimary = Color(0xFF202020),
    primaryContainer = Color(0xFF424242), onPrimaryContainer = Color.White,
    secondary = Color(0xFFBDBDBD), onSecondary = Color(0xFF202020),
    secondaryContainer = Color(0xFF383838), onSecondaryContainer = Color(0xFFECECEC),
    tertiary = Color(0xFFD0D0D0), onTertiary = Color(0xFF202020),
    background = Color(0xFF121212), onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF1C1C1C), onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF292929), onSurfaceVariant = Color(0xFFD0D0D0),
    outline = Color(0xFF858585)
)

@Composable
fun DockTheme(mode: String, dynamicColors: Boolean = true, content: @Composable () -> Unit) {
    val dark = when (mode) { "claro" -> false; "escuro" -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = when {
        dynamicColors && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DockDarkColors
        else -> DockLightColors
    }
    val view = LocalView.current
    SideEffect {                                              // ícones da barra de status/navegação conforme o tema escolhido
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val c = WindowCompat.getInsetsController(window, view)
        c.isAppearanceLightStatusBars = !dark
        c.isAppearanceLightNavigationBars = !dark
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
