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

Unit tests run on the JVM under Robolectric (`@Config(sdk = [34])`) — 22 tests in
`ClientOrganizerTest`, `ClientRepositoryTest`, `TelegramClientCatalogTest`,
`ScreenRenderingTest`, `AppActionsTest`.

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
