package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

private val DarkColorScheme = darkColorScheme(
    primary = KeePassGreenPrimary,
    onPrimary = Color(0xFF022C22),
    secondary = GoldPrimary,
    onSecondary = Color(0xFF1E1000),
    tertiary = KeePassGreenAccent,
    onTertiary = Color(0xFF022C22),
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = BorderDark,
    error = RoseError
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF059669),
    onPrimary = Color.White,
    secondary = Color(0xFFD97706),
    tertiary = Color(0xFF10B981),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF64748B)
)

@Composable
fun CartinoTheme(
    themeMode: String = "DARK",
    accentPalette: String = "GREEN",
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemDark
    }

    val primaryColor = when (accentPalette) {
        "GOLD" -> GoldPrimary
        "CYAN" -> PrimaryCyan
        "PURPLE" -> Color(0xFF8B5CF6)
        "EMERALD" -> Color(0xFF059669)
        else -> KeePassGreenPrimary
    }

    val secondaryColor = when (accentPalette) {
        "GOLD" -> PrimaryCyan
        "CYAN" -> GoldPrimary
        "PURPLE" -> Color(0xFFEC4899)
        "EMERALD" -> GoldPrimary
        else -> GoldPrimary
    }

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = primaryColor,
            onPrimary = Color(0xFF022C22),
            secondary = secondaryColor,
            onSecondary = Color(0xFF1E1000),
            tertiary = KeePassGreenAccent,
            onTertiary = Color(0xFF022C22),
            background = BackgroundDark,
            surface = SurfaceDark,
            surfaceVariant = SurfaceVariantDark,
            onBackground = TextPrimaryDark,
            onSurface = TextPrimaryDark,
            onSurfaceVariant = TextSecondaryDark,
            outline = BorderDark,
            error = RoseError
        )
    } else {
        lightColorScheme(
            primary = primaryColor,
            onPrimary = Color.White,
            secondary = secondaryColor,
            tertiary = Color(0xFF10B981),
            background = Color(0xFFF8FAFC),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF1F5F9),
            onBackground = Color(0xFF0F172A),
            onSurface = Color(0xFF0F172A),
            onSurfaceVariant = Color(0xFF64748B)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides TextStyle(fontFamily = VazirmatnFontFamily)
        ) {
            content()
        }
    }
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CartinoTheme(
        themeMode = if (darkTheme) "DARK" else "LIGHT",
        content = content
    )
}
