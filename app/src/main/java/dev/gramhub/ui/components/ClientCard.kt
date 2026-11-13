package dev.gramhub.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.gramhub.R
import dev.gramhub.data.TelegramClient

/**
 * One row of the launcher list: the real app icon, its real label and the
 * package name as secondary information.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClientCard(
    client: TelegramClient,
    isFavorite: Boolean,
    isHidden: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 12.dp,
    iconSize: Dp = AppIconSize,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val container by animateColorAsState(
        targetValue = if (isHidden) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "cardContainer",
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.more_actions),
                role = Role.Button,
            ),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = if (isHidden) 0.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppIcon(drawable = client.icon, size = iconSize)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = client.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isHidden) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = client.secondaryLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (trailingContent != null) {
                trailingContent()
            } else if (isFavorite) {
                FavoriteBadge()
            }
        }
    }
}

@Composable
fun FavoriteBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = stringResource(R.string.pin),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(16.dp),
        )
    }
}

private fun TelegramClient.secondaryLine(): String {
    val version = versionName?.takeIf { it.isNotBlank() }
    return if (version == null) packageName else "$packageName \u00b7 $version"
}
