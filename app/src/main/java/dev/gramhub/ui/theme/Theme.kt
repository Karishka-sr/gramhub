package dev.gramhub.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.gramhub.data.DesignStyle
import dev.gramhub.data.GramHubSettings
import dev.gramhub.data.ThemeMode

@Composable
fun GramHubTheme(
    settings: GramHubSettings,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current

    MaterialTheme(
        colorScheme = resolveColorScheme(settings, darkTheme, context),
        typography = GramHubTypography,
        shapes = resolveShapes(settings.designStyle),
        content = content,
    )
}

/**
 * Shapes for the two design styles.
 *
 * "Expressive" leans on the rounder Material 3 corner scale, so cards read as
 * soft pills and the list feels lighter; "standard" keeps the default scale, which
 * is what most Material 3 apps ship with.
 */
internal fun resolveShapes(style: DesignStyle): androidx.compose.material3.Shapes =
    when (style) {
        DesignStyle.STANDARD -> androidx.compose.material3.Shapes()
        DesignStyle.EXPRESSIVE -> androidx.compose.material3.Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp),
        )
    }

/**
 * Picks the colour scheme for [settings]. Dynamic colour wins over the AMOLED
 * switch because the wallpaper palette has no pure-black variant.
 */
internal fun resolveColorScheme(
    settings: GramHubSettings,
    darkTheme: Boolean,
    context: android.content.Context,
): androidx.compose.material3.ColorScheme {
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    return when {
        settings.dynamicColor && supportsDynamic && darkTheme -> dynamicDarkColorScheme(context)
        settings.dynamicColor && supportsDynamic -> dynamicLightColorScheme(context)
        darkTheme && settings.amoled -> AmoledDarkColors
        darkTheme -> DarkColors
        else -> LightColors
    }
}
