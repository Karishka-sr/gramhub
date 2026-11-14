package dev.gramhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ExtensionOff
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gramhub.R
import dev.gramhub.remote.RemoteClient
import dev.gramhub.ui.components.CatalogCard
import dev.gramhub.ui.components.ClientDetailSheet
import dev.gramhub.ui.components.InstallConfirmDialog
import dev.gramhub.ui.components.SearchField

/**
 * The "Not installed" tab.
 *
 * Every state is spelled out - loading, off, offline, empty, filtered-to-empty -
 * because "nothing here" is ambiguous and the user needs to know whether to
 * wait, retry, or change a setting.
 */
@Composable
fun CatalogContent(
    viewModel: MainViewModel,
    state: CatalogUiState,
    query: String,
    installState: InstallState?,
    scanning: Set<String>,
    fullCheckState: FullCheckState?,
    scanNotice: ScanNotice?,
    installed: List<dev.gramhub.data.TelegramClient>,
) {
    var detailTarget by remember { mutableStateOf<RemoteClient?>(null) }
    var confirmTarget by remember { mutableStateOf<RemoteClient?>(null) }
    var deleteTarget by remember { mutableStateOf<RemoteClient?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading && state.entries.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.catalog_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }

            !state.enabled -> CatalogEmpty(
                icon = Icons.Rounded.ExtensionOff,
                title = stringResource(R.string.catalog_disabled_title),
                message = stringResource(R.string.catalog_disabled_message),
                actionLabel = stringResource(R.string.settings_catalog_enabled),
                onAction = { viewModel.setCatalogEnabled(true) },
            )

            state.isOffline && state.entries.isEmpty() -> CatalogEmpty(
                icon = Icons.Rounded.CloudOff,
                title = stringResource(R.string.catalog_offline_title),
                message = stringResource(R.string.catalog_offline_message, state.serverUrl),
                actionLabel = stringResource(R.string.install_retry),
                onAction = { viewModel.refreshCatalog() },
            )

            state.isEmpty -> CatalogEmpty(
                icon = if (state.isSearching) Icons.Rounded.SearchOff else Icons.Rounded.ExtensionOff,
                title = stringResource(
                    if (state.isSearching) R.string.catalog_empty_search_title else R.string.catalog_empty_title,
                ),
                message = stringResource(
                    if (state.isSearching) R.string.catalog_empty_search_message else R.string.catalog_empty_message,
                ),
                actionLabel = if (state.isSearching) null else stringResource(R.string.install_retry),
                onAction = if (state.isSearching) null else ({ viewModel.refreshCatalog() }),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "catalog-search") {
                    SearchField(
                        query = query,
                        onQueryChange = viewModel::onCatalogQueryChange,
                        hint = stringResource(R.string.catalog_search_hint),
                    )
                }

                item(key = "catalog-count") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(start = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.catalog_count, state.entries.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        state.fetchedAt.takeIf { it > 0L }?.let { stamp ->
                            Text(
                                text = stringResource(
                                    R.string.catalog_updated_at,
                                    relativeTime(stamp),
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                items(state.entries, key = { it.id }) { entry ->
                    CatalogCard(
                        entry = entry,
                        installState = installState?.takeIf { it.clientId == entry.id },
                        isScanning = entry.id in scanning,
                        scanNotice = scanNotice?.takeIf { it.clientId == entry.id },
                        onClick = { detailTarget = entry },
                        onInstall = { confirmTarget = entry },
                        onCancelInstall = { viewModel.cancelInstall() },
                        onScan = { viewModel.scan(entry) },
                        onOpenRepository = { viewModel.openUrl(entry.repoUrl) },
                        onOpenReleasePage = {
                            viewModel.openUrl(entry.latestReleaseUrl ?: entry.repoUrl)
                        },
                    )
                }
            }
        }
    }

    detailTarget?.let { entry ->
        val installedClient = installed.firstOrNull {
            it.packageName == entry.packageName ||
                it.label.equals(entry.label, ignoreCase = true)
        }
        ClientDetailSheet(
            entry = entry,
            installed = installedClient,
            hasDownload = viewModel.hasDownload(entry),
            installState = installState?.takeIf { it.clientId == entry.id },
            fullCheckState = fullCheckState?.takeIf { it.clientId == entry.id },
            isScanning = entry.id in scanning,
            scanNotice = scanNotice,
            onDismiss = {
                detailTarget = null
                viewModel.clearFullCheck()
                viewModel.clearScanMessage()
            },
            onInstall = { confirmTarget = entry },
            onCancelInstall = { viewModel.cancelInstall() },
            onCheck = { viewModel.scan(entry) },
            onFullCheck = { viewModel.runFullCheck(entry) },
            onDelete = { deleteTarget = entry },
            onOpen = { installedClient?.let(viewModel::launch) },
            onOpenRepository = { viewModel.openUrl(entry.repoUrl) },
            onOpenRelease = { viewModel.openUrl(entry.latestReleaseUrl ?: entry.repoUrl) },
        )
    }

    confirmTarget?.let { entry ->
        InstallConfirmDialog(
            entry = entry,
            onConfirm = {
                confirmTarget = null
                viewModel.install(entry)
            },
            onDismiss = { confirmTarget = null },
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.client_delete_confirm_title)) },
            text = { Text(stringResource(R.string.client_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteDownload(entry)
                        deleteTarget = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.client_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * Turns a fetch timestamp into "только что", "5 минут назад", "2 часа назад".
 *
 * The unit strings come from resources so both locales read naturally rather
 * than being glued together from English words.
 */
@Composable
private fun relativeTime(stamp: Long): String {
    val minutes = ((System.currentTimeMillis() - stamp) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1L -> stringResource(R.string.catalog_updated_now)
        minutes < 60L -> stringResource(R.string.catalog_updated_minutes, minutes)
        minutes < 24L * 60L -> stringResource(R.string.catalog_updated_hours, minutes / 60L)
        else -> stringResource(R.string.catalog_updated_days, minutes / (24L * 60L))
    }
}

@Composable
private fun CatalogEmpty(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Text(text = title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) {
                Text(actionLabel)
            }
        }
    }
}
