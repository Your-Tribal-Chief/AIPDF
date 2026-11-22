package com.allpdf.pro.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.allpdf.pro.core.Store

val BrandRed = Color(0xFFE5322D)

private val Light = lightColorScheme(
    primary = BrandRed, onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6), onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF384B70), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE3F8), onSecondaryContainer = Color(0xFF0F1C35),
    background = Color(0xFFF7F7FA), surface = Color.White, surfaceVariant = Color(0xFFF0F0F4),
    onSurface = Color(0xFF1B1B1F), onSurfaceVariant = Color(0xFF5B5D66), outline = Color(0xFFD5D6DD),
    surfaceContainer = Color(0xFFF3F3F7), surfaceContainerHigh = Color(0xFFECECF1), surfaceContainerLow = Color(0xFFFAFAFC),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF6B5F), onPrimary = Color(0xFF410002),
    primaryContainer = Color(0xFF8C1D18), onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFB7C6EA), onSecondary = Color(0xFF1F2F4E),
    secondaryContainer = Color(0xFF2E3D5C), onSecondaryContainer = Color(0xFFDDE3F8),
    background = Color(0xFF111216), surface = Color(0xFF1A1B20), surfaceVariant = Color(0xFF23242A),
    onSurface = Color(0xFFE4E4EA), onSurfaceVariant = Color(0xFFB0B2BC), outline = Color(0xFF3A3C44),
    surfaceContainer = Color(0xFF1E1F25), surfaceContainerHigh = Color(0xFF26272E), surfaceContainerLow = Color(0xFF16171B),
)

@Composable
fun isDark(): Boolean = when (Store.darkMode.value) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }

@Composable
fun AllPdfTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = if (isDark()) Dark else Light,
        typography = base.copy(
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
        ),
        content = content,
    )
}
