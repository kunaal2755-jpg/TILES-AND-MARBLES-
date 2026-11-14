package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private fun buildLightScheme(accentColor: Color = RoyalNavy) = lightColorScheme(
    primary = accentColor,
    onPrimary = Color.White,
    primaryContainer = NavyTint,
    onPrimaryContainer = Color.White,
    secondary = AmberGold,
    onSecondary = RoyalNavy,
    secondaryContainer = AmberGoldSoft,
    onSecondaryContainer = AmberGoldDark,
    tertiary = SecondaryNavy,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = SlateDark,
    surface = CardWhite,
    onSurface = SlateDark,
    surfaceVariant = Color(0xFFEEF2F6),
    onSurfaceVariant = SlateMuted,
    outline = BorderLight,
    error = DangerRed,
    onError = Color.White,
    errorContainer = DangerRedSoft,
    onErrorContainer = DangerRed
)

private val DarkColorScheme = darkColorScheme(
    primary = AmberGold,
    onPrimary = RoyalNavy,
    primaryContainer = SecondaryNavy,
    onPrimaryContainer = Color.White,
    secondary = AmberGold,
    onSecondary = RoyalNavy,
    background = RoyalNavy,
    onBackground = Color.White,
    surface = SecondaryNavy,
    onSurface = Color.White,
    surfaceVariant = NavyTint,
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF334155),
    error = Color(0xFFF87171),
    onError = RoyalNavy
)

val TileVaultShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun TileVaultTheme(
    darkTheme: Boolean = false,
    customPrimaryHex: String? = null,
    content: @Composable () -> Unit,
) {
    val parsedPrimary = customPrimaryHex
        ?.takeIf { it.startsWith("#") && (it.length == 7 || it.length == 9) }
        ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        ?: RoyalNavy

    val colorScheme = if (darkTheme) DarkColorScheme else buildLightScheme(parsedPrimary)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TileVaultTypography,
        shapes = TileVaultShapes,
        content = content
    )
}
