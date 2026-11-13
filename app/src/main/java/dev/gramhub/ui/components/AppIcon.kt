package dev.gramhub.ui.components

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/**
 * Renders the real launcher icon of an installed app.
 *
 * The drawable is rasterised once per size change instead of being converted on
 * every recomposition, and adaptive icons are rasterised at the requested pixel
 * size so they stay sharp on high-density screens.
 */
@Composable
fun AppIcon(
    drawable: Drawable,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val px = with(density) { size.roundToPx() }.coerceAtLeast(1)

    val bitmap = remember(drawable, px) { rasteriseIcon(drawable, px) }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.6f),
            )
        }
    }
}

/**
 * Rasterises a launcher icon at [px] pixels square, or null when the drawable
 * cannot be drawn. Adaptive icons are rendered at the requested size, so they
 * stay sharp on every density.
 */
internal fun rasteriseIcon(drawable: Drawable, px: Int): Bitmap? =
    runCatching { drawable.toBitmap(px.coerceAtLeast(1), px.coerceAtLeast(1)) }.getOrNull()

/** Standard icon size used across the launcher list. */
val AppIconSize: Dp = 48.dp
