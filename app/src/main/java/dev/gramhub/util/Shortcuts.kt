package dev.gramhub.util

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import dev.gramhub.data.TelegramClient

/**
 * Publishes the "default client" as a dynamic app shortcut, so it can be started
 * by long-pressing the GramHub icon on the home screen.
 */
object Shortcuts {

    private const val SHORTCUT_ID = "default_client"
    private const val ICON_SIZE = 192

    fun sync(context: Context, client: TelegramClient?) {
        if (client == null) {
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(SHORTCUT_ID))
            return
        }

        val target = AppActions.launchIntentFor(context, client.packageName)
            ?: AppActions.openAppInfoIntent(context, client.packageName)
            ?: return

        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(client.label)
            .setLongLabel(client.label)
            .setIcon(IconCompat.createWithBitmap(client.icon.toBitmap(ICON_SIZE, ICON_SIZE)))
            .setIntent(target)
            .build()

        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(context, listOf(shortcut))
        }
    }

}
