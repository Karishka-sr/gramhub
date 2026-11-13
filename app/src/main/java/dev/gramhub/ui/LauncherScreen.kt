package dev.gramhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gramhub.R
import dev.gramhub.data.GramHubSettings
import dev.gramhub.data.ListDensity
import dev.gramhub.data.SortMode
import dev.gramhub.ui.components.ClientActionSheet
import dev.gramhub.ui.components.ClientCard
import dev.gramhub.ui.components.EmptyIcon
import dev.gramhub.ui.components.EmptyState
import dev.gramhub.ui.components.NoResultsIcon
import dev.gramhub.ui.components.SearchField
import dev.gramhub.ui.components.UninstallDialog
import dev.gramhub.util.Haptics

@Composable
fun LauncherContent(
    viewModel: MainViewModel,
    state: LauncherUiState,
    settings: GramHubSettings,
    query: String,
) {
    val context = LocalContext.current

    var sheetPackage by remember { mutableStateOf<String?>(null) }
    var pendingUninstall by remember { mutableStateOf<String?>(null) }

    val sheetRow = state.rows.firstOrNull { it.client.packageName == sheetPackage }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.isEmpty -> EmptyState(
                icon = if (state.isSearching) NoResultsIcon else EmptyIcon,
                title = stringResource(
                    if (state.isSearching) R.string.empty_search_title else R.string.empty_title,
                ),
                message = stringResource(
                    if (state.isSearching) R.string.empty_search_message else R.string.empty_message,
                ),
                actionLabel = if (state.isSearching) null else stringResource(R.string.empty_button),
                onAction = if (state.isSearching) null else viewModel::refresh,
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(settings.cardSpacing()),
            ) {
                item(key = "search") {
                    SearchField(
                        query = query,
                        onQueryChange = viewModel::onQueryChange,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                if (settings.sortMode == SortMode.CUSTOM && !settings.pinFavorites) {
                    item(key = "custom-hint") {
                        Text(
                            text = stringResource(R.string.settings_sort_custom_hint),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        )
                    }
                }

                items(state.rows, key = { it.client.packageName }) { row ->
                    val reorderable = settings.sortMode == SortMode.CUSTOM
                    ClientCard(
                        client = row.client,
                        isFavorite = row.isFavorite,
                        isHidden = row.isHidden,
                        verticalPadding = settings.rowVerticalPadding(),
                        iconSize = settings.iconSize(),
                        onClick = { viewModel.launch(row.client) },
                        onLongClick = {
                            if (settings.hapticOnLongPress) Haptics.tap(context)
                            sheetPackage = row.client.packageName
                        },
                        trailingContent = if (reorderable) {
                            {
                                ReorderButtons(
                                    onUp = { viewModel.moveInCustomOrder(row.client.packageName, true) },
                                    onDown = { viewModel.moveInCustomOrder(row.client.packageName, false) },
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    if (sheetRow != null) {
        val row = sheetRow
        ClientActionSheet(
            client = row.client,
            isFavorite = row.isFavorite,
            isHidden = row.isHidden,
            onDismiss = { sheetPackage = null },
            onOpen = {
                sheetPackage = null
                viewModel.launch(row.client)
            },
            onToggleFavorite = {
                viewModel.toggleFavorite(row.client.packageName)
                sheetPackage = null
            },
            onToggleHidden = {
                viewModel.toggleHidden(row.client.packageName)
                sheetPackage = null
            },
            onAppInfo = {
                sheetPackage = null
                viewModel.openAppInfo(row.client)
            },
            onUninstall = {
                sheetPackage = null
                if (settings.confirmUninstall) {
                    pendingUninstall = row.client.packageName
                } else {
                    viewModel.requestUninstall(row.client)
                }
            },
        )
    }

    val uninstallTarget = state.rows.firstOrNull { it.client.packageName == pendingUninstall }
    if (uninstallTarget != null) {
        UninstallDialog(
            label = uninstallTarget.client.label,
            onConfirm = {
                viewModel.requestUninstall(uninstallTarget.client)
                pendingUninstall = null
            },
            onDismiss = { pendingUninstall = null },
        )
    }
}

@Composable
private fun ReorderButtons(onUp: () -> Unit, onDown: () -> Unit) {
    androidx.compose.foundation.layout.Row {
        IconButton(onClick = onUp) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onDown) {
            Icon(Icons.Rounded.ArrowDownward, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

private fun GramHubSettings.cardSpacing() = when (density) {
    ListDensity.COMPACT -> 6.dp
    ListDensity.COZY -> 10.dp
    ListDensity.COMFORTABLE -> 14.dp
}

private fun GramHubSettings.rowVerticalPadding() = when (density) {
    ListDensity.COMPACT -> 8.dp
    ListDensity.COZY -> 12.dp
    ListDensity.COMFORTABLE -> 16.dp
}

private fun GramHubSettings.iconSize() = when (density) {
    ListDensity.COMPACT -> 40.dp
    ListDensity.COZY -> 48.dp
    ListDensity.COMFORTABLE -> 56.dp
}
