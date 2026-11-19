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
    primaryContainer = TealSoftWash,
    onPrimaryContainer = RoyalNavy,
    secondary = AmberGold,
    onSecondary = DeepTealDark,
    secondaryContainer = AmberGoldSoft,
    onSecondaryContainer = AmberGoldDark,
    tertiary = SecondaryNavy,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = SlateDark,
    surface = CardWhite,
    onSurface = SlateDark,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = SlateMuted,
    outline = BorderLight,
    error = DangerRed,
    onError = Color.White,
    errorContainer = DangerRedSoft,
    onErrorContainer = DangerRed
)

private val DarkColorScheme = darkColorScheme(
    primary = AmberGold,
    onPrimary = DeepTealDark,
    primaryContainer = SecondaryNavy,
    onPrimaryContainer = Color.White,
    secondary = AmberGold,
    onSecondary = DeepTealDark,
    background = DeepTealDark,
    onBackground = Color.White,
    surface = RoyalNavy,
    onSurface = Color.White,
    surfaceVariant = SecondaryNavy,
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF28595C),
    error = Color(0xFFF87171),
    onError = DeepTealDark
)

val TileVaultShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

@Composable
fun TileVaultTheme(
    darkTheme: Boolean = false,
    customPrimaryHex: String? = null,
    content: @Composable () -> Unit,
) {
    // Migrate legacy default navy hex transparently to the new architectural teal unless user picked a custom brand color
    val normalizedHex = customPrimaryHex?.trim()
    val parsedPrimary = if (normalizedHex.equals("#0B192C", ignoreCase = true) || normalizedHex.isNullOrBlank()) {
        RoyalNavy
    } else {
        normalizedHex
            .takeIf { it.startsWith("#") && (it.length == 7 || it.length == 9) }
            ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
            ?: RoyalNavy
    }

    val colorScheme = if (darkTheme) DarkColorScheme else buildLightScheme(parsedPrimary)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TileVaultTypography,
        shapes = TileVaultShapes,
        content = content
    )
}
