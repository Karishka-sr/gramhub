package dev.gramhub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gramhub.data.AutoRefresh
import dev.gramhub.data.CatalogJoiner
import dev.gramhub.data.ClientOrganizer
import dev.gramhub.data.ClientRepository
import dev.gramhub.data.DesignStyle
import dev.gramhub.data.GramHubSettings
import dev.gramhub.data.ListDensity
import dev.gramhub.data.SettingsRepository
import dev.gramhub.data.SortMode
import dev.gramhub.data.TelegramClient
import dev.gramhub.data.ThemeMode
import dev.gramhub.remote.ApkFullCheck
import dev.gramhub.remote.ApkInstaller
import dev.gramhub.remote.CatalogApi
import dev.gramhub.remote.CatalogSource
import dev.gramhub.remote.DownloadProgress
import dev.gramhub.remote.FullCheckOutcome
import dev.gramhub.remote.FullCheckReport
import dev.gramhub.remote.InstallOutcome
import dev.gramhub.remote.RemoteClient
import dev.gramhub.remote.ScanOutcome
import dev.gramhub.util.AppActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
    /** Wall-clock time of the last successful fetch, for the "updated N ago" line. */
    val fetchedAt: Long = 0L,
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
    /** When the catalog was last fetched successfully; 0 means never. */
    val fetchedAt: Long = 0L,
) {
    val isEmpty: Boolean get() = !isLoading && entries.isEmpty()
    val isSearching: Boolean get() = query.isNotBlank()
    val isShowingCache: Boolean get() = source == CatalogSource.CACHE
    val isOffline: Boolean get() = source == CatalogSource.NONE && error != null
    /** True while a background re-fetch is running on top of already-shown data. */
    val isRefreshing: Boolean get() = isLoading && entries.isNotEmpty()
}

/** Result of the connection test in settings. */
enum class HealthState { UNKNOWN, CHECKING, OK, FAILED }

/**
 * Why the last "Проверить" attempt produced no verdict.
 *
 * [UNSUPPORTED] is the normal case for the default static catalog: there is no
 * server to scan on demand, so the UI explains that instead of looking dead.
 */
enum class ScanMessage { UNSUPPORTED, FAILED }

/**
 * A [ScanMessage] together with the entry whose button produced it, so the card
 * and the detail sheet can show the explanation only where it belongs.
 */
data class ScanNotice(val clientId: String, val message: ScanMessage)

/**
 * State of a full on-device verification of one catalog entry's APK.
 *
 * [Phase.DOWNLOADING] is part of the flow because the full check has to run on
 * real bytes: when nothing is on disk yet, the sheet fetches the APK first and
 * then verifies it, without handing anything to the installer.
 */
data class FullCheckState(
    val clientId: String,
    val phase: Phase,
    val fraction: Float = 0f,
    val step: String = "",
    val report: FullCheckReport? = null,
    val error: String? = null,
) {
    enum class Phase { DOWNLOADING, RUNNING, DONE, FAILED }
}

/** A download or install currently in flight, for one catalog entry. */
data class InstallState(
    val clientId: String,
    val phase: Phase,
    val message: String? = null,
    /** Bytes received so far, for the progress readout. */
    val downloadedBytes: Long = 0L,
    /** Expected total size, or 0 when unknown. */
    val totalBytes: Long = 0L,
) {
    enum class Phase { DOWNLOADING, VERIFYING, INSTALLING, DONE, FAILED }

    /** Fraction complete in 0..1, or null when the total size is unknown. */
    val fraction: Float?
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val clients = ClientRepository(application)
    private val settingsRepo = SettingsRepository(application)
    private val installer = ApkInstaller(application)
    private val fullCheck = ApkFullCheck(application)
    private val catalog = CatalogApi(application) { settings.value.catalogUrl }

    val settings: StateFlow<GramHubSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, GramHubSettings())

    private val _query = MutableStateFlow("")
    private val _catalogQuery = MutableStateFlow("")
    private val _remote = MutableStateFlow(RemoteCatalog())
    private val _installState = MutableStateFlow<InstallState?>(null)
    private val _fullCheckState = MutableStateFlow<FullCheckState?>(null)
    private val _scanning = MutableStateFlow<Set<String>>(emptySet())
    private val _scanMessage = MutableStateFlow<ScanNotice?>(null)
    private val _health = MutableStateFlow<HealthState>(HealthState.UNKNOWN)

    /** A one-shot message key; the UI shows it and calls [consumeMessage]. */
    private val _message = MutableStateFlow<String?>(null)

    /** The in-flight catalog fetch, so a manual refresh can supersede a poll. */
    private var _catalogJob: Job? = null

    /** Incremented per fetch; a superseded fetch sees a stale number and drops its result. */
    private var _catalogGeneration = 0

    /** The in-flight install, so the user can abort it mid-download. */
    private var _installJob: Job? = null

    val query: StateFlow<String> = _query.asStateFlow()
    val catalogQuery: StateFlow<String> = _catalogQuery.asStateFlow()
    val installState: StateFlow<InstallState?> = _installState.asStateFlow()

    /** The full check currently running, or its last result, if any. */
    val fullCheckState: StateFlow<FullCheckState?> = _fullCheckState.asStateFlow()

    /** Ids of catalog entries whose scan is in flight, so the card can show it. */
    val scanning: StateFlow<Set<String>> = _scanning.asStateFlow()

    /** Why the last "Проверить" produced no verdict, or null when there was none. */
    val scanMessage: StateFlow<ScanNotice?> = _scanMessage.asStateFlow()

    /** Result of the last "test the connection" press in settings. */
    val health: StateFlow<HealthState> = _health.asStateFlow()
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Incremented by the refresh button to force a re-scan of installed apps. */
    private val refreshTrigger = MutableStateFlow(0)

    /**
     * Package names the catalog vouches for, so a client installed from it is
     * recognised as a Telegram client even when its fork package id is not in the
     * built-in list. Defaults to the built-in list until the catalog has loaded.
     */
    private val catalogPackages: StateFlow<Set<String>> = _remote
        .map { remote -> remote.entries.mapNotNull { it.packageName }.filter { it.isNotBlank() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val uiState: StateFlow<LauncherUiState> = combine(
        clients.clients(catalogPackages),
        settings,
        _query,
        refreshTrigger,
    ) { all, prefs, query, _ ->
        buildState(all, prefs, query)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LauncherUiState())

    /**
     * The "Not installed" tab.
     *
     * Derived from the cached catalog plus the live installed list, so installing
     * or uninstalling an app moves it between tabs immediately without another
     * network call.
     */
    val catalogState: StateFlow<CatalogUiState> = combine(
        _remote,
        clients.clients(catalogPackages),
        settings,
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
            fetchedAt = remote.fetchedAt,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogUiState())

    init {
        viewModelScope.launch {
            // DataStore delivers the saved address asynchronously, so a refresh
            // fired at construction time would query the built-in default instead
            // of the server the user configured. Reacting to the address itself
            // waits for it and also re-fetches when it is changed in Settings.
            //
            // This listens to `settings`, the same StateFlow `catalogState` is
            // built from, rather than to the repository directly: two separate
            // subscriptions to DataStore can deliver a new address at different
            // moments, which let a fetch read the old URL while the UI already
            // showed the new one. One shared flow keeps them in step.
            settings
                .map { it.catalogUrl }
                .distinctUntilChanged()
                .collect { refreshCatalog() }
        }

        // Background polling, so a client someone publishes on GitHub appears on
        // its own. The interval is read live, so changing it in Settings restarts
        // the loop without a relaunch.
        viewModelScope.launch {
            settings
                .map { it.autoRefresh }
                .distinctUntilChanged()
                .collectLatest { mode ->
                    val interval = mode.millis
                    if (interval <= 0L) return@collectLatest
                    while (true) {
                        delay(interval)
                        refreshCatalog(silent = true)
                    }
                }
        }
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

    fun refreshCatalog(silent: Boolean = false) {
        // A background poll and a manual refresh can overlap; the newer one wins
        // and the older is dropped, so the "updating" spinner never gets stuck on
        // and two fetches cannot interleave their results.
        if (_catalogJob?.isActive == true) {
            if (silent) return
            _catalogJob?.cancel()
        }

        val generation = ++_catalogGeneration
        _catalogJob = viewModelScope.launch {
            val previousFetch = _remote.value.fetchedAt
            _remote.update { it.copy(isLoading = true, error = null) }

            val result = catalog.fetchClients(forceRefresh = true)

            // Cancelling the previous job only stops it at its next suspension
            // point, and its blocking request has usually already finished by
            // then, so it would publish its stale answer on top of this one. The
            // generation check makes a superseded fetch drop its result instead.
            if (generation != _catalogGeneration) return@launch

            _remote.value = RemoteCatalog(
                entries = result.clients,
                isLoading = false,
                source = result.source,
                error = result.error,
                fetchedAt = if (result.source == CatalogSource.NETWORK) {
                    System.currentTimeMillis()
                } else {
                    previousFetch
                },
            )
        }
    }

    fun onCatalogQueryChange(value: String) {
        _catalogQuery.value = value
    }

    fun consumeMessage() {
        _message.value = null
    }

    // --------------------------------------------------------------- installing

    fun install(entry: RemoteClient) {
        // A second tap must not start a parallel download for the same entry.
        if (_installJob?.isActive == true) return

        _installJob = viewModelScope.launch {
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
                _installState.value = InstallState(
                    entry.id,
                    InstallState.Phase.DOWNLOADING,
                    totalBytes = apk.size.coerceAtLeast(0L),
                )
                val failure = runCatching {
                    installer.download(apk, target).collect { progress ->
                        when (progress) {
                            is DownloadProgress.Progress -> _installState.value = InstallState(
                                entry.id,
                                InstallState.Phase.DOWNLOADING,
                                downloadedBytes = progress.downloaded,
                                totalBytes = if (progress.total > 0) progress.total else apk.size.coerceAtLeast(0L),
                            )

                            is DownloadProgress.Failed -> throw IllegalStateException(progress.reason)

                            is DownloadProgress.Finished -> Unit
                        }
                    }
                }.exceptionOrNull()

                // Cancellation arrives here as a CancellationException, which is a
                // normal end for an aborted install, not a failure to report.
                if (failure is kotlinx.coroutines.CancellationException) {
                    installer.cancelDownload(target)
                    _installState.value = null
                    return@launch
                }

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
                    // The system installer cannot be recalled once it is on screen,
                    // so this is the last point where the app can still be stopped.
                    if (!isActive) return@launch
                    _installState.value = InstallState(entry.id, InstallState.Phase.INSTALLING)
                    installer.promptInstall(outcome.file)
                    _installState.value = InstallState(entry.id, InstallState.Phase.DONE)
                    // The package broadcast will arrive on its own; this makes the
                    // "Installed" tab move the entry over without waiting for it.
                    refresh()
                }
            }
        }
    }

    /**
     * Aborts the install the user started, mid-download or mid-verify.
     *
     * Cancelling the coroutine stops the progress loop, and [ApkInstaller] then
     * removes the system download job and deletes the partial file, so nothing is
     * left behind for the next attempt to trip over.
     */
    fun cancelInstall() {
        val job = _installJob
        if (job?.isActive != true) {
            _installState.value = null
            return
        }
        job.cancel()
        _installState.value = null
    }

    /** Asks the catalog for this entry's verdict, then stores it. */
    fun scan(entry: RemoteClient) = viewModelScope.launch {
        _scanMessage.value = null
        _scanning.update { it + entry.id }
        val outcome = catalog.scan(entry.id)
        _scanning.update { it - entry.id }

        when (outcome) {
            is ScanOutcome.Verdict -> _remote.update { current ->
                current.copy(
                    entries = current.entries.map {
                        if (it.id == entry.id) it.copy(scan = outcome.scan) else it
                    },
                )
            }

            // The snapshot the app reads has no on-demand endpoint. Say so where
            // the button is, not in a snackbar hidden behind the open sheet.
            ScanOutcome.Unavailable -> _scanMessage.value =
                ScanNotice(entry.id, ScanMessage.UNSUPPORTED)

            is ScanOutcome.Failed -> _scanMessage.value =
                ScanNotice(entry.id, ScanMessage.FAILED)
        }
    }

    /** Asks the server whether it is reachable, for the settings screen. */
    fun testConnection() = viewModelScope.launch {
        _health.value = HealthState.CHECKING
        _health.value = if (catalog.health()) HealthState.OK else HealthState.FAILED
    }

    /**
     * Runs the full on-device check for one catalog entry.
     *
     * The APK has to be on disk first, so an entry that was never downloaded is
     * fetched here. Nothing is handed to the installer: the check only reads the
     * file and reports what it finds.
     */
    fun runFullCheck(entry: RemoteClient) = viewModelScope.launch {
        val apk = entry.apk
        if (apk == null) {
            _fullCheckState.value = FullCheckState(entry.id, FullCheckState.Phase.FAILED, error = "no_apk")
            return@launch
        }

        val target = installer.fileFor(entry)

        if (!installer.hasDownload(entry)) {
            _fullCheckState.value = FullCheckState(
                entry.id,
                FullCheckState.Phase.DOWNLOADING,
                step = "download",
            )
            val failure = runCatching {
                installer.download(apk, target).collect { progress ->
                    when (progress) {
                        is DownloadProgress.Progress -> {
                            val total = if (progress.total > 0) progress.total else apk.size
                            val fraction = if (total > 0) progress.downloaded.toFloat() / total else 0f
                            _fullCheckState.value = FullCheckState(
                                entry.id,
                                FullCheckState.Phase.DOWNLOADING,
                                fraction = fraction.coerceIn(0f, 1f),
                                step = "download",
                            )
                        }

                        is DownloadProgress.Failed -> throw IllegalStateException(progress.reason)

                        is DownloadProgress.Finished -> Unit
                    }
                }
            }.exceptionOrNull()

            if (failure != null) {
                _fullCheckState.value = FullCheckState(
                    entry.id,
                    FullCheckState.Phase.FAILED,
                    error = failure.message ?: "download_failed",
                )
                return@launch
            }
        }

        val file = installer.resolveDownloaded(target.name) ?: target
        _fullCheckState.value = FullCheckState(entry.id, FullCheckState.Phase.RUNNING, step = "scan")

        val outcome = fullCheck.verify(file, apk.sha256) { progress ->
            _fullCheckState.value = FullCheckState(
                entry.id,
                FullCheckState.Phase.RUNNING,
                fraction = progress.fraction,
                step = progress.step,
            )
        }

        _fullCheckState.value = when (outcome) {
            is FullCheckOutcome.Completed -> FullCheckState(
                entry.id,
                FullCheckState.Phase.DONE,
                fraction = 1f,
                report = outcome.report,
            )

            is FullCheckOutcome.Failed -> FullCheckState(
                entry.id,
                FullCheckState.Phase.FAILED,
                error = outcome.reason,
            )
        }
    }

    fun clearFullCheck() {
        _fullCheckState.value = null
    }

    /** Clears the "Проверить" explanation once the sheet has shown it. */
    fun clearScanMessage() {
        _scanMessage.value = null
    }

    /** True when a verified download for this entry is already on disk. */
    fun hasDownload(entry: RemoteClient): Boolean = installer.hasDownload(entry)

    /** Deletes the downloaded APK of an entry and clears any in-flight install. */
    fun deleteDownload(entry: RemoteClient) {
        _installJob?.cancel()
        installer.cancelDownload(installer.fileFor(entry))
        _installState.value = null
        if (_fullCheckState.value?.clientId == entry.id) _fullCheckState.value = null
    }

    fun resetHealth() {
        _health.value = HealthState.UNKNOWN
    }

    /** Drops the APK of an entry, e.g. after a failed install. */
    fun discardDownload(entry: RemoteClient) {
        _installJob?.cancel()
        installer.cancelDownload(installer.fileFor(entry))
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

    /**
     * Called when the app comes back to the foreground.
     *
     * A new client published while the app was in the background should be there
     * by the time the user looks at the screen, so the catalog is re-read on
     * resume unless the user turned that off. The installed list is always
     * re-scanned, since an install may have finished outside the app.
     */
    fun onResume() {
        refreshTrigger.value += 1
        if (settings.value.refreshOnResume) refreshCatalog()
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
    fun setDesignStyle(style: DesignStyle) = viewModelScope.launch { settingsRepo.setDesignStyle(style) }
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
    fun setAutoRefresh(mode: AutoRefresh) = viewModelScope.launch {
        settingsRepo.setAutoRefresh(mode)
    }
    fun setRefreshOnResume(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setRefreshOnResume(enabled)
    }
    fun resetSettings() = viewModelScope.launch { settingsRepo.reset() }
}
