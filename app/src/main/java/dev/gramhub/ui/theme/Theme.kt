package dev.gramhub.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
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
        content = content,
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
