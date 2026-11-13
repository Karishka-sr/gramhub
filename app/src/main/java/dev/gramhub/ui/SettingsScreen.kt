package dev.gramhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gramhub.BuildConfig
import dev.gramhub.R
import dev.gramhub.data.GramHubSettings
import dev.gramhub.data.ListDensity
import dev.gramhub.data.SortMode
import dev.gramhub.data.TelegramClientCatalog
import dev.gramhub.data.ThemeMode
import dev.gramhub.ui.HealthState
import dev.gramhub.ui.components.AppIcon
import dev.gramhub.ui.components.EmptyState
import dev.gramhub.ui.components.EmptyIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()

    var showResetDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "appearance") {
                SettingsGroup(title = stringResource(R.string.settings_group_appearance)) {
                    SectionLabel(R.string.settings_theme)
                    ThemePicker(
                        current = settings.themeMode,
                        onSelect = viewModel::setThemeMode,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    SwitchRow(
                        title = stringResource(R.string.settings_dynamic_color),
                        summary = stringResource(R.string.settings_dynamic_color_summary),
                        checked = settings.dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor,
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_amoled),
                        summary = stringResource(R.string.settings_amoled_summary),
                        checked = settings.amoled,
                        onCheckedChange = viewModel::setAmoled,
                        enabled = settings.themeMode != ThemeMode.LIGHT,
                    )
                }
            }

            item(key = "list") {
                SettingsGroup(title = stringResource(R.string.settings_group_list)) {
                    SectionLabel(R.string.settings_density)
                    DensityPicker(current = settings.density, onSelect = viewModel::setDensity)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    SectionLabel(R.string.settings_sort)
                    SortPicker(current = settings.sortMode, onSelect = viewModel::setSortMode)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    SwitchRow(
                        title = stringResource(R.string.settings_show_other),
                        summary = stringResource(R.string.settings_show_other_summary),
                        checked = settings.showOtherApps,
                        onCheckedChange = viewModel::setShowOtherApps,
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_pin_favorites),
                        summary = stringResource(R.string.settings_pin_favorites_summary),
                        checked = settings.pinFavorites,
                        onCheckedChange = viewModel::setPinFavorites,
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_show_hidden),
                        summary = stringResource(R.string.settings_show_hidden_summary),
                        checked = settings.showHidden,
                        onCheckedChange = viewModel::setShowHidden,
                    )
                }
            }

            item(key = "catalog") {
                SettingsGroup(title = stringResource(R.string.settings_group_catalog)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_catalog_enabled),
                        summary = stringResource(R.string.settings_catalog_enabled_summary),
                        checked = settings.catalogEnabled,
                        onCheckedChange = viewModel::setCatalogEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    CatalogUrlRow(
                        current = settings.catalogUrl,
                        onSave = viewModel::setCatalogUrl,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    CatalogActionsRow(
                        health = health,
                        onTest = viewModel::testConnection,
                        onReload = {
                            viewModel.resetHealth()
                            viewModel.refreshCatalog()
                        },
                    )
                }
            }

            item(key = "default-client") {
                SettingsGroup(title = stringResource(R.string.settings_default_client)) {
                    Text(
                        text = stringResource(R.string.settings_default_client_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    DefaultClientPicker(
                        clients = state.rows.map { it.client },
                        selected = settings.defaultClientPackage,
                        onSelect = viewModel::setDefaultClient,
                    )
                }
            }

            item(key = "behavior") {
                SettingsGroup(title = stringResource(R.string.settings_group_behavior)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_haptic),
                        summary = stringResource(R.string.settings_haptic_summary),
                        checked = settings.hapticOnLongPress,
                        onCheckedChange = viewModel::setHaptic,
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_confirm_uninstall),
                        summary = stringResource(R.string.settings_confirm_uninstall_summary),
                        checked = settings.confirmUninstall,
                        onCheckedChange = viewModel::setConfirmUninstall,
                    )
                    HiddenClientsRow(
                        hiddenCount = state.hiddenCount,
                        showHidden = settings.showHidden,
                        onToggle = viewModel::setShowHidden,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_reset)) },
                        supportingContent = { Text(stringResource(R.string.settings_reset_summary)) },
                        trailingContent = {
                            IconButton(onClick = { showResetDialog = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.RestartAlt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }

            item(key = "about") {
                SettingsGroup(title = stringResource(R.string.settings_group_about)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_clients_found, state.totalFound)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.settings_version,
                                    BuildConfig.VERSION_NAME,
                                ),
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    Text(
                        text = stringResource(R.string.settings_about_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.settings_reset_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reset_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetSettings()
                        showResetDialog = false
                    },
                ) { Text(stringResource(R.string.settings_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}

@Composable
private fun SectionLabel(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun ThemePicker(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    ChipRow {
        FilterChip(
            selected = current == ThemeMode.SYSTEM,
            onClick = { onSelect(ThemeMode.SYSTEM) },
            label = { Text(stringResource(R.string.settings_theme_system)) },
            leadingIcon = if (current == ThemeMode.SYSTEM) {
                { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
            } else {
                null
            },
        )
        FilterChip(
            selected = current == ThemeMode.LIGHT,
            onClick = { onSelect(ThemeMode.LIGHT) },
            label = { Text(stringResource(R.string.settings_theme_light)) },
        )
        FilterChip(
            selected = current == ThemeMode.DARK,
            onClick = { onSelect(ThemeMode.DARK) },
            label = { Text(stringResource(R.string.settings_theme_dark)) },
        )
    }
}

@Composable
private fun DensityPicker(current: ListDensity, onSelect: (ListDensity) -> Unit) {
    ChipRow {
        FilterChip(
            selected = current == ListDensity.COMPACT,
            onClick = { onSelect(ListDensity.COMPACT) },
            label = { Text(stringResource(R.string.settings_density_compact)) },
        )
        FilterChip(
            selected = current == ListDensity.COZY,
            onClick = { onSelect(ListDensity.COZY) },
            label = { Text(stringResource(R.string.settings_density_cozy)) },
        )
        FilterChip(
            selected = current == ListDensity.COMFORTABLE,
            onClick = { onSelect(ListDensity.COMFORTABLE) },
            label = { Text(stringResource(R.string.settings_density_comfortable)) },
        )
    }
}

@Composable
private fun SortPicker(current: SortMode, onSelect: (SortMode) -> Unit) {
    Column(modifier = Modifier.selectableGroup()) {
        SortOption(R.string.settings_sort_name, SortMode.NAME, current, onSelect)
        SortOption(R.string.settings_sort_installed, SortMode.INSTALLED, current, onSelect)
        SortOption(R.string.settings_sort_custom, SortMode.CUSTOM, current, onSelect)
    }
}

@Composable
private fun SortOption(
    labelRes: Int,
    value: SortMode,
    current: SortMode,
    onSelect: (SortMode) -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(labelRes)) },
        leadingContent = {
            RadioButton(selected = current == value, onClick = { onSelect(value) })
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun DefaultClientPicker(
    clients: List<dev.gramhub.data.TelegramClient>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Column(modifier = Modifier.selectableGroup()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_default_client_none)) },
            leadingContent = {
                RadioButton(selected = selected == null, onClick = { onSelect(null) })
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )

        clients.forEach { client ->
            ListItem(
                headlineContent = { Text(client.label) },
                supportingContent = { Text(client.packageName) },
                leadingContent = { AppIcon(drawable = client.icon, size = 32.dp) },
                trailingContent = {
                    RadioButton(
                        selected = selected == client.packageName,
                        onClick = { onSelect(client.packageName) },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun HiddenClientsRow(hiddenCount: Int, showHidden: Boolean, onToggle: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_hidden_manage)) },
        supportingContent = { Text(stringResource(R.string.hidden_count, hiddenCount)) },
        trailingContent = { Switch(checked = showHidden, onCheckedChange = onToggle) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * Editor for the catalog server address.
 *
 * The address is committed when the field loses focus or the button is pressed,
 * rather than on every keystroke, so a half-typed URL never becomes the
 * configured one.
 */
@Composable
private fun CatalogUrlRow(current: String, onSave: (String) -> Unit) {
    var draft by remember(current) { mutableStateOf(current) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.settings_catalog_url)) },
            supportingText = { Text(stringResource(R.string.settings_catalog_url_summary)) },
            placeholder = { Text(stringResource(R.string.settings_catalog_url_hint)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSave(draft) }),
            trailingIcon = {
                if (draft != current) {
                    IconButton(onClick = { onSave(draft) }) {
                        Icon(
                            imageVector = Icons.Rounded.Save,
                            contentDescription = stringResource(R.string.settings_catalog_saved),
                        )
                    }
                }
            },
        )

        // A plain-HTTP catalog can be rewritten by anyone on the same network, so
        // say so rather than letting the user believe the verdicts are trustworthy.
        if (draft.startsWith("http://")) {
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.settings_catalog_insecure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The connection test and the manual catalog refresh.
 *
 * The test result is shown inline next to the button rather than as a toast,
 * because it is the answer to a question the user just asked.
 */
@Composable
private fun CatalogActionsRow(
    health: HealthState,
    onTest: () -> Unit,
    onReload: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = onTest, enabled = health != HealthState.CHECKING) {
                Text(stringResource(R.string.settings_catalog_test))
            }
            TextButton(onClick = onReload) {
                Text(stringResource(R.string.settings_catalog_reload))
            }
        }

        when (health) {
            HealthState.CHECKING -> Text(
                text = stringResource(R.string.verdict_checking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            HealthState.OK -> Text(
                text = stringResource(R.string.settings_catalog_ok),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )

            HealthState.FAILED -> Text(
                text = stringResource(R.string.settings_catalog_fail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )

            HealthState.UNKNOWN -> Unit
        }
    }
}
