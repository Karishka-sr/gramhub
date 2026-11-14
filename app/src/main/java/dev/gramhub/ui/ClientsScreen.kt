package dev.gramhub.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.HideSource
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gramhub.R

/** Which half of the launcher is showing. */
private enum class ClientsTab(val label: Int) {
    INSTALLED(R.string.tab_installed),
    AVAILABLE(R.string.tab_available),
}

/**
 * The "My clients" screen.
 *
 * Two tabs: the clients already on the device, and the ones the catalog can
 * supply. The tab row sits under a shared app bar so the search box and the
 * refresh action stay in the same place in both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val catalogState by viewModel.catalogState.collectAsStateWithLifecycle()
    val catalogQuery by viewModel.catalogQuery.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val installState by viewModel.installState.collectAsStateWithLifecycle()
    val fullCheckState by viewModel.fullCheckState.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(ClientsTab.INSTALLED.ordinal) }
    val snackbarHost = remember { SnackbarHostState() }

    val installedLabel = stringResource(R.string.tab_installed)
    val availableLabel = stringResource(R.string.tab_available)

    val installFailedText = stringResource(R.string.install_failed)
    val installPermissionText = stringResource(R.string.install_permission)
    val scanFailedText = stringResource(R.string.verdict_scan_failed)
    val noApkText = stringResource(R.string.install_no_apk)

    // Install failures and permission prompts are surfaced as a snackbar rather
    // than inside the card, because they can happen while the sheet is closed.
    LaunchedEffect(message) {
        val text = when (message) {
            null -> null
            "install_permission" -> installPermissionText
            "scan_failed" -> scanFailedText
            "no_apk" -> noApkText
            else -> installFailedText
        }
        if (text != null) {
            viewModel.consumeMessage()
            snackbarHost.showSnackbar(text)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.launcher_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    if (tab == ClientsTab.INSTALLED.ordinal && state.hiddenCount > 0) {
                        Icon(
                            imageVector = Icons.Rounded.HideSource,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.hidden_count, state.hiddenCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            androidx.compose.foundation.layout.Column {
                SecondaryTabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == ClientsTab.INSTALLED.ordinal,
                        onClick = { tab = ClientsTab.INSTALLED.ordinal },
                        text = { Text(installedLabel) },
                    )
                    Tab(
                        selected = tab == ClientsTab.AVAILABLE.ordinal,
                        onClick = { tab = ClientsTab.AVAILABLE.ordinal },
                        text = { Text(availableLabel) },
                    )
                }

                AnimatedVisibility(visible = catalogState.isShowingCache) {
                    OfflineNotice(
                        text = stringResource(R.string.catalog_cached_notice),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                when (tab) {
                    ClientsTab.AVAILABLE.ordinal -> CatalogContent(
                        viewModel = viewModel,
                        state = catalogState,
                        query = catalogQuery,
                        installState = installState,
                        scanning = scanning,
                        fullCheckState = fullCheckState,
                        installed = state.rows.map { it.client },
                    )

                    else -> LauncherContent(
                        viewModel = viewModel,
                        state = state,
                        settings = settings,
                        query = query,
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineNotice(text: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
