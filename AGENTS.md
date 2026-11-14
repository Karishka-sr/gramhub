# GramHub — repository notes

GramHub is an Android "My clients" launcher: it discovers the Telegram clients
installed on the device, shows them with their real icons and labels, and starts
the one the user taps. Jetpack Compose + Material 3, single `:app` module.

## Build and test

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew :app:testDebugUnitTest :app:assembleDebug     # APK -> app/build/outputs/apk/debug/
./gradlew :app:assembleRelease                          # unsigned release APK
```

Toolchain: AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01, `compileSdk` 35,
`minSdk` 24. Android SDK lives at `$HOME/android-sdk` (`local.properties`).

Unit tests run on the JVM under Robolectric (`@Config(sdk = [34])`) — 70 tests
across 14 classes, including `ClientOrganizerTest`, `ClientRepositoryTest`,
`TelegramClientCatalogTest`, `CatalogJsonTest`, `ScanOutcomeParsingTest`,
`MainViewModelTest`, `ClientDetailSheetTest`, `ScreenRenderingTest` and
`AppActionsTest`.

## Things that are easy to get wrong

- **Starting another app.** `AppActions.launchIntentFor` resolves the target with
  `queryIntentActivities` and starts it by explicit component. Do not switch back
  to `resolveActivity`: a launcher activity declares only
  `MAIN` + `LAUNCHER` without `CATEGORY_DEFAULT`, so `resolveActivity` returns
  null even for perfectly launchable apps. Intents also carry
  `FLAG_ACTIVITY_NEW_TASK`, because they are started from an application context
  (the view model holds no activity) — without the flag the platform throws
  "Calling startActivity() from outside of an Activity context".
- **Compose screenshot testing.** `captureToImage()` is unsupported under
  Robolectric. Assert on semantics instead; icon rendering is covered by calling
  the pure `rasteriseIcon` function directly.
- **Client detection.** `TelegramClientCatalog` combines a curated package list
  with whole-word label keywords (Latin + Cyrillic). Keyword matches are
  "probable" and are hidden until *Settings → Apps with telegram in their name*
  is enabled, so new tests must not assume they show up by default.
- **Downloading a catalog APK.** `ApkInstaller.download` uses `DownloadManager`,
  which writes to the *external* files dir (`Android/data/<pkg>/files/Download`),
  because it cannot write to app-private storage. Two consequences: `hasDownload`
  must resolve that external path (not `fileFor`'s private one, or every install
  re-downloads), and `res/xml/file_paths.xml` must declare
  `<external-files-path path="Download/"/>` or `FileProvider.getUriForFile`
  throws and the app dies right after verifying the download.
- **Completion broadcasts are unreliable.** `ACTION_DOWNLOAD_COMPLETE` can fire
  before the receiver is registered, so `download()` also polls
  `DownloadManager.Query` for `STATUS_SUCCESSFUL`/`STATUS_FAILED`. Do not remove
  the poller: without it the UI sits on "Downloading…" forever on a slow link.
  Over the sandbox tunnel a 44 MB APK takes several minutes, which is normal.

## The catalog server

`server/` is a FastAPI app that discovers Telegram clients from GitHub and scans
their release APKs. Run it with the app factory, not an `app` attribute:

```bash
cd server
PYTHONPATH=. GRAMHUB_DATA_DIR=/tmp/gh_live \
  python3 -m uvicorn gramhub_server.api:create_app --factory --host 0.0.0.0 --port 8080
PYTHONPATH=. pytest tests -q        # 83 tests, ~70 s (needs JAVA_HOME set)
```

The app's default catalog address is the public HTTPS URL
`https://work-2-qvcblhvqskzkxwwx.prod-runtime.all-hands.dev`, which is the sandbox
port 12001 exposed through the runtime proxy. `http://10.0.2.2:8080` also works,
but only from the emulator, because 10.0.2.2 is the host machine as the emulator
sees it — a real phone cannot resolve it. That mismatch is why the app used to
report "the server did not respond" on a physical device.

To serve the catalog yourself, run the server on the port the proxy expects and
point the app at the resulting public address (Settings -> Client catalog):

```bash
cd server
PYTHONPATH=. GRAMHUB_DATA_DIR=/tmp/gh_live \
  python3 -m uvicorn gramhub_server.api:create_app --factory --host 0.0.0.0 --port 12001
```

## Release signing

The release APK is signed with a real, long-lived key:

- Keystore: `keystore/gramhub-release.jks` (PKCS12, alias `gramhub`)
- Key: RSA 4096, SHA384withRSA, valid until 2054
- Credentials: `keystore.properties` at the repository root

Both files are gitignored on purpose — a signing key must never be committed.
Back them up somewhere safe: losing the key means you can never publish an update
that installs over an existing install, because Android rejects an APK whose
signing certificate differs from the installed one.

`app/build.gradle.kts` reads `keystore.properties` and falls back to an unsigned
build when it is absent, so a fresh clone still builds without the key.

## Things that are easy to get wrong

- **Verdict calibration.** The scanner has three permission tiers in
  `server/gramhub_server/config.py`: `EXPECTED_PERMISSIONS` (fine),
  `SENSITIVE_PERMISSIONS` (reported as a MEDIUM finding, does not change the
  verdict) and `HIGH_RISK_PERMISSIONS` (makes the verdict DANGEROUS). A
  permission must never appear in two tiers, and nothing in
  `HIGH_RISK_PERMISSIONS` may also be in `EXPECTED_PERMISSIONS` — that
  contradiction marks every real client as malicious. `TestPermissionPolicy`
  enforces this. `REQUEST_INSTALL_PACKAGES`, `SYSTEM_ALERT_WINDOW`,
  `READ_CALL_LOG` and `READ_PHONE_NUMBERS` are all declared by the real Telegram
  forks, so they belong in the sensitive tier.
- **`SCANNER_VERSION`.** Bump it in `server/gramhub_server/scanner.py` (and
  `Settings.scanner_version`) whenever a verdict could change. Cached scans are
  keyed by it, so without a bump the server keeps serving old verdicts.
- **Cleartext HTTP.** The catalog is self-hosted and normally plain HTTP, which
  Android blocks from API 28 on. `res/xml/network_security_config.xml` permits it
  and `SettingsScreen` warns when the address is not HTTPS. Removing that config
  silently breaks the default server address.

## Localisation

User-facing strings live in `values/` (Russian, the default) and `values-en/`
(English); both files must keep the same keys. `resourceConfigurations` in
`app/build.gradle.kts` limits shipped locales to `en` and `ru`.

## Emulator notes

The test emulator is software-rendered with no KVM, so it is slow: allow ~40-60 s
after `am start` before taking a screenshot or a `uiautomator dump`, and expect
occasional "System UI isn't responding" dialogs that need dismissing.

## Catalog delivery

The app's default catalog address is a **static snapshot**, not a running
server: `docs/clients.json`, served by GitHub Pages at
`https://karishka-sr.github.io/gramhub/clients.json`. This keeps clients visible
with no host to maintain and no tunnel that can expire.

- Regenerate it with `GITHUB_TOKEN=... python tools/refresh_catalog.py -o docs/clients.json`
  (discovers from GitHub, downloads and scans each APK, and writes the snapshot in
  one step; `--no-scan` reuses cached verdicts). `tools/export_catalog.py` only
  re-serialises an existing database.
- `.github/workflows/refresh-catalog.yml` refreshes it daily. It is kept as a
  local file only: pushing a workflow needs a token with the `workflow` scope,
  and the available tokens (`ghu_` app token, classic `ghp_` with `repo`) do not
  have it. Add the file to the repository by hand when a suitably scoped token is
  available.
- `CatalogApi` accepts either a live server (`https://host`, appends `/clients`
  and can POST `/scan`) or a direct `.json` link (fetched as-is; `health()`
  degrades gracefully, and `scan()` re-reads the snapshot for its embedded
  verdict). The two are told apart by the `.json` suffix.
- The committed snapshot is parsed by `StaticCatalogSnapshotTest`, so a broken
  export fails the build rather than shipping an empty catalog.

## Recognising installed clients

`ClientRepository` matches installed apps against `TelegramClientCatalog`, which
knows a fixed package list plus label keywords. A fork installed from the catalog
can have an arbitrary package id and a name with no "Telegram"/"gram" in it, so
it would never appear in the "Installed" tab. `MainViewModel` therefore feeds the
catalog's package names into `clients(catalogPackages)` as `extraPackages`, and
`matchReason` accepts them as `KNOWN_PACKAGE`. The hard denylist still wins.

## Client detail sheet and checks

Tapping a catalog card opens `ClientDetailSheet`, which is the only place the
three checks live, so they cannot be confused:

- **Проверить** (server scan) — `MainViewModel.scan`, calls `CatalogApi.scan`.
  Against a live server that is `POST /clients/{id}/scan`. Against the default
  static snapshot there is no endpoint, so it re-fetches the snapshot instead:
  the refresh workflow bakes the verdicts into it, so re-reading it is how a
  fresh verdict arrives. When the snapshot carries none, the button reports
  `ScanOutcome.Unavailable` and the UI says why (see below) instead of doing
  nothing visible.
- **Проверить полностью** (on-device) — `ApkFullCheck` in `remote/FullCheck.kt`.
  Reads the actual downloaded APK: verifies every ZIP entry against its stored
  CRC-32, parses the DEX header for a class count, scans DEX bytes for known-bad
  indicators, and reads the package id plus signer certificates through
  `PackageManager.getPackageArchiveInfo`. If nothing is on disk it downloads the
  APK first without handing it to the installer.

Note: `java.util.zip.ZipFile` streams do **not** verify entry CRCs on the JVM, so
`drainEntry` computes CRC-32 itself; do not "simplify" it back to a bare read.

The server's findings are rendered inline through `SecurityContent` (extracted
from `SecuritySheet`), not as a second stacked sheet.

When "Проверить" produces no verdict, `MainViewModel` sets a `ScanNotice`
(the entry id plus a `ScanMessage`), which the card and the sheet render through
`ScanNoticeRow` under the button that produced it. It is deliberately not a
snackbar: a snackbar would be hidden behind the open sheet and would cover the
card button. The notice carries the entry id so it only appears on its own card.

The snapshot's verdicts are the only ones the default install ever shows, so
`tools/refresh_catalog.py` must scan while generating it. That needs
`apksigner`/`aapt2` (Android build-tools), which is why the refresh workflow
installs them; without them every exported verdict is null.

## Interface style

`GramHubSettings.designStyle` (`DesignStyle.STANDARD` / `EXPRESSIVE`) is applied
in `ui/theme/Theme.kt` via `resolveShapes`, changing the Material 3 corner scale
across cards, sheets and chips. The picker sits in Settings under the
"Список клиентов" group, not "Внешний вид".

No emoji is used anywhere in the UI: every indicator is a Material icon, including
the catalog star (`Icons.Rounded.Star` next to the count, not a `★` glyph).

## Community link

The project's Telegram channel is `GramHubSettings.CHANNEL_URL` (`https://t.me/NeonBeatt`),
surfaced as the "Сообщество" group at the bottom of Settings and as a card on
`docs/index.html`.

## Session state (resume here)

Branch `master`. The latest commit is "Show catalog verdicts from the static
snapshot"; nothing is pushed and no PR is open. Build is green:
`server/.venv/bin/python -m pytest -q` reports **85 passed** and
`./gradlew testDebugUnitTest` reports **70 passed** (14 classes).
`docs/clients.json` holds 20 clients, all 20 with a verdict (18 caution,
1 dangerous, 1 trusted). Version is `1.2.1` (versionCode 4).

This session made the catalog's verdicts visible in the default install. The
catalog is a static JSON snapshot, not a running server, so "Проверить" had no
endpoint to call and did nothing visible. Now:

- `tools/refresh_catalog.py` downloads and scans each entry's newest APK while
  writing the snapshot (`--no-scan` reuses cached verdicts). It needs
  Android build-tools for `apksigner`/`aapt2`; the refresh workflow installs them.
- `CatalogApi.scan` returns `ScanOutcome` (`Verdict` / `Unavailable` / `Failed`);
  against a static snapshot it re-reads the snapshot and takes the embedded
  verdict for the entry.
- The card and the detail sheet show a `ScanNoticeRow` under the button when a
  check yields nothing, so the button never looks dead.

Two bugs from the previous session are fixed and verified on device:

1. **ANR when switching the Clients tabs.** `TabRow` is subcompose-based and
   measured tab-label intrinsics on every frame, which froze the main thread for
   5-10 s under the emulator's software renderer. `ClientsScreen.kt` now uses
   `SecondaryTabRow`, which has fixed-height tabs and does no subcompose. Verified:
   rapid back-and-forth switching between "Installed" and "Not installed" produces
   no ANR dialog and no `Input dispatching timed out` in logcat; the only ANRs left
   are Google Play Services' own (system, from the slow emulation).

2. **"Catalog unreachable" on the first launch.** `MainViewModel` fired
   `refreshCatalog()` from `init`, but the address lives in DataStore and is
   delivered asynchronously, so that first request always went to the built-in
   default. The `init` block now follows `settingsRepo.settings.map { it.catalogUrl
   }.distinctUntilChanged()` and refetches, which waits for the saved value and
   also re-fetches when the address is edited in Settings.
   `app/src/test/java/dev/gramhub/ui/MainViewModelTest.kt` drives the real HTTP path
   against a local socket server and fails if the view model stops following the
   configured address.

Both derived state flows (`uiState`, `catalogState`) also moved their join / sort /
filter work onto `Dispatchers.Default` via `flowOn`, since the catalog can hold
dozens of entries.

Verified on device this session (emulator-5554, `dev.gramhub.debug`):

- Cold start with no interaction loads the catalog straight from the configured
  server (10 entries, `GET /clients 200`), no Retry needed.
- Settings renders Appearance / Theme / List / Sorting / Behavior / Client catalog
  groups; the "Material Design style" picker (Classic M3 / Expressive M3) is under
  "Client list". Version row reads from `BuildConfig.VERSION_NAME`.
- No emoji anywhere in `app/src/main` (scanned programmatically); every indicator is
  a Material icon.

### Running against the local server

The emulator here has no working network route (`10.0.2.2` is "Network is
unreachable", no DNS), so use `adb reverse` and point the app at `127.0.0.1`:

```
server/.venv/bin/python -m uvicorn gramhub_server.api:create_app --factory --host 0.0.0.0 --port 8080
adb reverse tcp:8080 tcp:8080
# set the catalog address to http://127.0.0.1:8080 in Settings
```

The DataStore file can also be written directly to skip the UI step:
`app/src/main/.../gramhub_settings.preferences_pb` under the app's
`files/datastore/` directory (protobuf: key entry with field 1 = name, field 2 =
value message whose field 5 holds the string).

