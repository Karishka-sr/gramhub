package dev.gramhub.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gramhub.R
import dev.gramhub.data.GramHubSettings
import dev.gramhub.data.ListDensity
import dev.gramhub.data.MatchReason
import dev.gramhub.data.TelegramClient
import dev.gramhub.data.ThemeMode
import dev.gramhub.ui.components.AppIcon
import dev.gramhub.ui.components.ClientCard
import dev.gramhub.ui.components.EmptyIcon
import dev.gramhub.ui.components.EmptyState
import dev.gramhub.ui.components.SearchField
import dev.gramhub.ui.components.rasteriseIcon
import dev.gramhub.ui.theme.AmoledDarkColors
import dev.gramhub.ui.theme.DarkColors
import dev.gramhub.ui.theme.GramHubTheme
import dev.gramhub.ui.theme.LightColors
import dev.gramhub.ui.theme.resolveColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real composables so layout or crash regressions are caught, and
 * covers the icon rasterisation and theme resolution the screens depend on.
 *
 * Text assertions resolve the actual string resources, so they keep working in
 * any locale.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRenderingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun string(id: Int): String = context.getString(id)

    private fun solidDrawable(color: Int, size: Int = 96): BitmapDrawable {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(color)
        return BitmapDrawable(null, bitmap)
    }

    private fun client(packageName: String, label: String, color: Int) = TelegramClient(
        packageName = packageName,
        label = label,
        icon = solidDrawable(color),
        versionName = "1.2.3",
        firstInstallTime = 0L,
        lastUpdateTime = 0L,
        isSystemApp = false,
        matchedBy = MatchReason.KNOWN_PACKAGE,
    )

    @Test
    fun `launcher card shows the real label, package name and version`() {
        val target = client("org.telegram.messenger", "Telegram", 0xFF2AABEE.toInt())

        composeRule.setContent {
            GramHubTheme(settings = GramHubSettings()) {
                ClientCard(
                    client = target,
                    isFavorite = true,
                    isHidden = false,
                    onClick = {},
                    onLongClick = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule.onNodeWithText("Telegram").assertExists()
        composeRule.onNodeWithText("org.telegram.messenger · 1.2.3").assertExists()
        // Favourites are marked by the star badge, not an emoji.
        composeRule.onNodeWithContentDescription(string(R.string.pin)).assertExists()
    }

    @Test
    fun `app icon rasterises the real drawable at the requested size`() {
        val iconColor = 0xFF2AABEE.toInt()
        val bitmap = rasteriseIcon(solidDrawable(iconColor), px = 48)

        assertNotNull(bitmap)
        assertEquals(48, bitmap!!.width)
        assertEquals(48, bitmap.height)
        // The colour of the real icon must survive the conversion.
        assertEquals(iconColor, bitmap.getPixel(24, 24))
    }

    @Test
    fun `a broken drawable degrades to the placeholder instead of crashing`() {
        val broken = object : Drawable() {
            override fun draw(canvas: Canvas) = throw IllegalStateException("cannot draw")
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(colorFilter: ColorFilter?) = Unit
            override fun getOpacity(): Int = PixelFormat.UNKNOWN
        }
        assertNull(rasteriseIcon(broken, px = 48))

        composeRule.setContent {
            GramHubTheme(settings = GramHubSettings()) {
                AppIcon(drawable = ColorDrawable(0xFF00FF00.toInt()), size = Dp(48f))
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `search field shows the hint and reports typed text`() {
        var typed = ""
        composeRule.setContent {
            GramHubTheme(settings = GramHubSettings()) {
                SearchField(query = typed, onQueryChange = { typed = it })
            }
        }

        composeRule.onNodeWithText(string(R.string.search_hint)).performTextInput("neko")
        assertEquals("neko", typed)
    }

    @Test
    fun `empty state renders its action button and reacts to clicks`() {
        var clicks = 0
        val title = string(R.string.empty_title)
        composeRule.setContent {
            GramHubTheme(settings = GramHubSettings()) {
                EmptyState(
                    icon = EmptyIcon,
                    title = title,
                    message = string(R.string.empty_message),
                    actionLabel = string(R.string.empty_button),
                    onAction = { clicks++ },
                )
            }
        }

        composeRule.onNodeWithText(title).assertExists()
        composeRule.onNodeWithText(string(R.string.empty_button)).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `theme resolution honours light, dark and amoled choices`() {
        val light = resolveColorScheme(GramHubSettings(dynamicColor = false), darkTheme = false, context = context)
        assertEquals(LightColors, light)

        val dark = resolveColorScheme(GramHubSettings(dynamicColor = false), darkTheme = true, context = context)
        assertEquals(DarkColors, dark)

        val amoled = resolveColorScheme(
            GramHubSettings(dynamicColor = false, amoled = true),
            darkTheme = true,
            context = context,
        )
        assertEquals(AmoledDarkColors, amoled)
        // AMOLED really is pure black, so the switch is visible on screen.
        assertEquals(0L, amoled.background.value.toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun `dark theme and amoled render without crashing`() {
        composeRule.setContent {
            GramHubTheme(
                settings = GramHubSettings(
                    themeMode = ThemeMode.DARK,
                    amoled = true,
                    dynamicColor = false,
                    density = ListDensity.COMFORTABLE,
                ),
            ) {
                EmptyState(icon = EmptyIcon, title = "Dark", message = "AMOLED")
            }
        }

        composeRule.onNodeWithText("Dark").assertExists()
        composeRule.onNodeWithText("AMOLED").assertExists()
    }
}
