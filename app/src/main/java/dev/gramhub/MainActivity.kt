package dev.gramhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gramhub.ui.ClientsScreen
import dev.gramhub.ui.MainViewModel
import dev.gramhub.ui.SettingsScreen
import dev.gramhub.ui.theme.GramHubTheme
import dev.gramhub.util.Shortcuts

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            GramHubTheme(settings = settings) {
                GramHubApp(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back to the app re-reads the catalog, so a client published while
        // the app was in the background is already there.
        viewModel.onResume()
    }
}

private enum class Tab { CLIENTS, SETTINGS }

@Composable
private fun GramHubApp(viewModel: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.CLIENTS) }

    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Keep the home-screen shortcut in sync with the chosen default client.
    LaunchedEffect(settings.defaultClientPackage, state.rows) {
        val client = state.rows.firstOrNull { it.client.packageName == settings.defaultClientPackage }
        Shortcuts.sync(context, client?.client)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.CLIENTS,
                    onClick = { tab = Tab.CLIENTS },
                    icon = { Icon(Icons.Rounded.Apps, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_clients)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.SETTINGS,
                    onClick = { tab = Tab.SETTINGS },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_settings)) },
                )
            }
        },
    ) { innerPadding ->
        // The screens own their top app bar and therefore their top inset; the outer
        // scaffold only contributes the navigation bar height.
        Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
            when (tab) {
                Tab.CLIENTS -> ClientsScreen(viewModel = viewModel)
                Tab.SETTINGS -> SettingsScreen(viewModel = viewModel)
            }
        }
    }
}
