package dev.gramhub.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gramhub.R
import dev.gramhub.data.TelegramClient

/**
 * Actions available for a client, opened by long-pressing its card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientActionSheet(
    client: TelegramClient,
    isFavorite: Boolean,
    isHidden: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleHidden: () -> Unit,
    onAppInfo: () -> Unit,
    onUninstall: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppIcon(drawable = client.icon, size = 56.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = client.label,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = client.packageName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            ActionRow(
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                label = stringResource(R.string.open),
                onClick = onOpen,
            )
            ActionRow(
                icon = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                label = stringResource(if (isFavorite) R.string.unpin else R.string.pin),
                onClick = onToggleFavorite,
            )
            ActionRow(
                icon = if (isHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                label = stringResource(if (isHidden) R.string.unhide else R.string.hide),
                onClick = onToggleHidden,
            )
            ActionRow(
                icon = Icons.Rounded.Info,
                label = stringResource(R.string.open_app_info),
                onClick = onAppInfo,
            )
            ActionRow(
                icon = Icons.Rounded.Delete,
                label = stringResource(R.string.uninstall),
                onClick = onUninstall,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    ListItem(
        headlineContent = { Text(label, color = tint) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}
