package dev.gramhub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gramhub.data.CatalogJoiner
import dev.gramhub.data.ClientOrganizer
import dev.gramhub.data.ClientRepository
import dev.gramhub.data.GramHubSettings
import dev.gramhub.data.ListDensity
import dev.gramhub.data.SettingsRepository
import dev.gramhub.data.SortMode
import dev.gramhub.data.TelegramClient
import dev.gramhub.data.ThemeMode
import dev.gramhub.remote.ApkInstaller
import dev.gramhub.remote.CatalogApi
import dev.gramhub.remote.CatalogSource
import dev.gramhub.remote.DownloadProgress
import dev.gramhub.remote.InstallOutcome
import dev.gramhub.remote.RemoteClient
import dev.gramhub.util.AppActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One visible row of the launcher list. */
data class ClientRow(
    val client: TelegramClient,
    val isFavorite: Boolean,
    val isHidden: Boolean,
)

/** Everything the launcher screen needs, already filtered and ordered. */
data class LauncherUiState(
    val rows: List<ClientRow> = emptyList(),
    val hiddenCount: Int = 0,
    val totalFound: Int = 0,
    val isLoading: Boolean = true,
    val query: String = "",
) {
    val isEmpty: Boolean get() = !isLoading && rows.isEmpty()
    val isSearching: Boolean get() = query.isNotBlank()
}

/** The raw catalog as fetched, before it is matched against installed apps. */
data class RemoteCatalog(
    val entries: List<RemoteClient> = emptyList(),
    val isLoading: Boolean = false,
    val source: CatalogSource = CatalogSource.NONE,
    val error: String? = null,
)

/** State of the "Not installed" tab. */
data class CatalogUiState(
    val entries: List<RemoteClient> = emptyList(),
    val isLoading: Boolean = false,
    val source: CatalogSource = CatalogSource.NONE,
    val error: String? = null,
    val query: String = "",
    val enabled: Boolean = true,
    val serverUrl: String = "",
) {
    val isEmpty: Boolean get() = !isLoading && entries.isEmpty()
    val isSearching: Boolean get() = query.isNotBlank()
    val isShowingCache: Boolean get() = source == CatalogSource.CACHE
    val isOffline: Boolean get() = source == CatalogSource.NONE && error != null
}

/** Result of the connection test in settings. */
enum class HealthState { UNKNOWN, CHECKING, OK, FAILED }

/** A download or install currently in flight, for one catalog entry. */
data class InstallState(
    val clientId: String,
    val phase: Phase,
    val message: String? = null,
) {
    enum class Phase { DOWNLOADING, VERIFYING, INSTALLING, DONE, FAILED }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val clients = ClientRepository(application)
    private val settingsRepo = SettingsRepository(application)
    private val installer = ApkInstaller(application)
    private val catalog = CatalogApi(application) { settings.value.catalogUrl }

    val settings: StateFlow<GramHubSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, GramHubSettings())

    private val _query = MutableStateFlow("")
    private val _catalogQuery = MutableStateFlow("")
    private val _remote = MutableStateFlow(RemoteCatalog())
    private val _installState = MutableStateFlow<InstallState?>(null)
    private val _scanning = MutableStateFlow<Set<String>>(emptySet())
    private val _health = MutableStateFlow<HealthState>(HealthState.UNKNOWN)

    /** A one-shot message key; the UI shows it and calls [consumeMessage]. */
    private val _message = MutableStateFlow<String?>(null)

    val query: StateFlow<String> = _query.asStateFlow()
    val catalogQuery: StateFlow<String> = _catalogQuery.asStateFlow()
    val installState: StateFlow<InstallState?> = _installState.asStateFlow()

    /** Ids of catalog entries whose scan is in flight, so the card can show it. */
    val scanning: StateFlow<Set<String>> = _scanning.asStateFlow()

    /** Result of the last "test the connection" press in settings. */
    val health: StateFlow<HealthState> = _health.asStateFlow()
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Incremented by the refresh button to force a re-scan of installed apps. */
    private val refreshTrigger = MutableStateFlow(0)

    val uiState: StateFlow<LauncherUiState> = combine(
        clients.clients(),
        settingsRepo.settings,
        _query,
        refreshTrigger,
    ) { all, prefs, query, _ ->
        buildState(all, prefs, query)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LauncherUiState())

    /**
     * The "Not installed" tab.
     *
     * Derived from the cached catalog plus the live installed list, so installing
     * or uninstalling an app moves it between tabs immediately without another
     * network call.
     */
    val catalogState: StateFlow<CatalogUiState> = combine(
        _remote,
        clients.clients(),
        settingsRepo.settings,
        _catalogQuery,
    ) { remote, installed, prefs, query ->
        val available = if (prefs.catalogEnabled) {
            CatalogJoiner.order(CatalogJoiner.notInstalled(remote.entries, installed))
        } else {
            emptyList()
        }

        CatalogUiState(
            entries = filterCatalog(available, query),
            isLoading = remote.isLoading,
            source = remote.source,
            error = remote.error,
            query = query,
            enabled = prefs.catalogEnabled,
            serverUrl = prefs.catalogUrl,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogUiState())

    init {
        refreshCatalog()
    }

    private fun buildState(
        all: List<TelegramClient>,
        prefs: GramHubSettings,
        query: String,
    ): LauncherUiState {
        val rows = ClientOrganizer
            .order(ClientOrganizer.visible(all, prefs, query), prefs)
            .map { client ->
                ClientRow(
                    client = client,
                    isFavorite = client.packageName in prefs.favorites,
                    isHidden = client.packageName in prefs.hidden,
                )
            }

        return LauncherUiState(
            rows = rows,
            hiddenCount = all.count { it.packageName in prefs.hidden },
            totalFound = all.size,
            isLoading = false,
            query = query,
        )
    }

    private fun filterCatalog(entries: List<RemoteClient>, query: String): List<RemoteClient> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return entries
        return entries.filter { entry ->
            entry.label.lowercase().contains(needle) ||
                entry.repo.lowercase().contains(needle) ||
                entry.description.lowercase().contains(needle) ||
                entry.packageName?.lowercase()?.contains(needle) == true
        }
    }

    // ----------------------------------------------------------------- catalog

    fun refreshCatalog() = viewModelScope.launch {
        _remote.update { it.copy(isLoading = true, error = null) }

        val result = catalog.fetchClients(forceRefresh = true)

        _remote.value = RemoteCatalog(
            entries = result.clients,
            isLoading = false,
            source = result.source,
            error = result.error,
        )
    }

    fun onCatalogQueryChange(value: String) {
        _catalogQuery.value = value
    }

    fun consumeMessage() {
        _message.value = null
    }

    // --------------------------------------------------------------- installing

    fun install(entry: RemoteClient) = viewModelScope.launch {
        val apk = entry.apk
        if (apk == null) {
            _message.value = "no_apk"
            return@launch
        }

        if (!installer.canInstallPackages()) {
            installer.requestInstallPermission()
            _message.value = "install_permission"
            return@launch
        }

        val target = installer.fileFor(entry)

        if (!installer.hasDownload(entry)) {
            _installState.value = InstallState(entry.id, InstallState.Phase.DOWNLOADING)
            val failure = runCatching {
                installer.download(apk, target).collect { progress ->
                    if (progress is DownloadProgress.Failed) {
                        throw IllegalStateException(progress.reason)
                    }
                }
            }.exceptionOrNull()

            if (failure != null) {
                _installState.value = InstallState(
                    entry.id,
                    InstallState.Phase.FAILED,
                    failure.message ?: "download_failed",
                )
                return@launch
            }
        }

        _installState.value = InstallState(entry.id, InstallState.Phase.VERIFYING)
        val file = installer.resolveDownloaded(target.name) ?: target

        when (val outcome = installer.verify(file, apk.sha256)) {
            is InstallOutcome.DigestMismatch -> {
                _installState.value = InstallState(
                    entry.id,
                    InstallState.Phase.FAILED,
                    "digest_mismatch",
                )
            }

            is InstallOutcome.Failed -> {
                _installState.value = InstallState(entry.id, InstallState.Phase.FAILED, outcome.reason)
            }

            is InstallOutcome.NeedsPermission -> {
                installer.requestInstallPermission()
                _installState.value = null
                _message.value = "install_permission"
            }

            is InstallOutcome.Ready -> {
                _installState.value = InstallState(entry.id, InstallState.Phase.INSTALLING)
                installer.promptInstall(outcome.file)
                _installState.value = InstallState(entry.id, InstallState.Phase.DONE)
            }
        }
    }

    /** Asks the server to download and scan this entry's APK, then stores the verdict. */
    fun scan(entry: RemoteClient) = viewModelScope.launch {
        _scanning.update { it + entry.id }
        val result = catalog.scan(entry.id)
        _scanning.update { it - entry.id }

        if (result == null) {
            _message.value = "scan_failed"
            return@launch
        }
        _remote.update { current ->
            current.copy(
                entries = current.entries.map {
                    if (it.id == entry.id) it.copy(scan = result) else it
                },
            )
        }
    }

    /** Asks the server whether it is reachable, for the settings screen. */
    fun testConnection() = viewModelScope.launch {
        _health.value = HealthState.CHECKING
        _health.value = if (catalog.health()) HealthState.OK else HealthState.FAILED
    }

    fun resetHealth() {
        _health.value = HealthState.UNKNOWN
    }

    fun cancelInstall() {
        _installState.value = null
    }

    /** Drops the APK of an entry, e.g. after a failed install. */
    fun discardDownload(entry: RemoteClient) {
        runCatching { installer.fileFor(entry).delete() }
        _installState.value = null
    }

    // ----------------------------------------------------------------- actions

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun refresh() {
        refreshTrigger.value += 1
        refreshCatalog()
    }

    fun launch(client: TelegramClient) {
        AppActions.launch(getApplication(), client.packageName)
    }

    fun openAppInfo(client: TelegramClient) {
        AppActions.openAppInfo(getApplication(), client.packageName)
    }

    fun openUrl(url: String) {
        AppActions.openUrl(getApplication(), url)
    }

    fun requestUninstall(client: TelegramClient) {
        AppActions.requestUninstall(getApplication(), client.packageName)
    }

    fun toggleFavorite(packageName: String) = viewModelScope.launch {
        settingsRepo.toggleFavorite(packageName)
    }

    fun toggleHidden(packageName: String) = viewModelScope.launch {
        settingsRepo.toggleHidden(packageName)
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepo.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { settingsRepo.setDynamicColor(enabled) }
    fun setAmoled(enabled: Boolean) = viewModelScope.launch { settingsRepo.setAmoled(enabled) }
    fun setDensity(density: ListDensity) = viewModelScope.launch { settingsRepo.setDensity(density) }
    fun setShowOtherApps(show: Boolean) = viewModelScope.launch { settingsRepo.setShowOtherApps(show) }
    fun setPinFavorites(pin: Boolean) = viewModelScope.launch { settingsRepo.setPinFavorites(pin) }
    fun setShowHidden(show: Boolean) = viewModelScope.launch { settingsRepo.setShowHidden(show) }
    fun setSortMode(mode: SortMode) = viewModelScope.launch { settingsRepo.setSortMode(mode) }
    fun setDefaultClient(packageName: String?) = viewModelScope.launch {
        settingsRepo.setDefaultClient(packageName)
    }
    fun setHaptic(enabled: Boolean) = viewModelScope.launch { settingsRepo.setHapticOnLongPress(enabled) }
    fun setConfirmUninstall(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setConfirmUninstall(enabled)
    }
    fun moveInCustomOrder(packageName: String, up: Boolean) = viewModelScope.launch {
        settingsRepo.moveInCustomOrder(packageName, up)
    }
    fun setCatalogUrl(url: String) = viewModelScope.launch {
        settingsRepo.setCatalogUrl(url)
        refreshCatalog()
    }
    fun setCatalogEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setCatalogEnabled(enabled)
        if (enabled) refreshCatalog()
    }
    fun resetSettings() = viewModelScope.launch { settingsRepo.reset() }
}
