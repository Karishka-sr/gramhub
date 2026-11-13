package dev.gramhub.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Telegram-flavoured Material 3 palette used when dynamic colour is unavailable
// (Android < 12) or switched off by the user.
private val TelegramBlue = Color(0xFF2AABEE)
private val TelegramBlueDark = Color(0xFF1C8DD6)
private val TelegramBlueContainer = Color(0xFFCDE7F7)
private val TelegramBlueContainerDark = Color(0xFF0B3A54)
private val TelegramTeal = Color(0xFF00A0B0)

val LightColors = lightColorScheme(
    primary = TelegramBlueDark,
    onPrimary = Color.White,
    primaryContainer = TelegramBlueContainer,
    onPrimaryContainer = Color(0xFF04223A),
    secondary = TelegramTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC6E9EC),
    onSecondaryContainer = Color(0xFF04282C),
    tertiary = Color(0xFF5C6BC0),
    onTertiary = Color.White,
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Color(0xFF41474D),
    outline = Color(0xFF71787E),
    outlineVariant = Color(0xFFC1C7CE),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD0FF),
    onPrimary = Color(0xFF00344F),
    primaryContainer = TelegramBlueContainerDark,
    onPrimaryContainer = Color(0xFFCDE7F7),
    secondary = Color(0xFF8AD3D9),
    onSecondary = Color(0xFF00363B),
    secondaryContainer = Color(0xFF004F57),
    onSecondaryContainer = Color(0xFFC6E9EC),
    tertiary = Color(0xFFB9C3FF),
    onTertiary = Color(0xFF23307A),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF41474D),
    onSurfaceVariant = Color(0xFFC1C7CE),
    outline = Color(0xFF8B9198),
    outlineVariant = Color(0xFF41474D),
)

/** Pure-black surfaces for OLED panels. */
val AmoledDarkColors = DarkColors.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainerHigh = Color(0xFF121212),
    surfaceContainerHighest = Color(0xFF161616),
)
