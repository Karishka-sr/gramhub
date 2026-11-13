"""Runtime configuration for the GramHub catalog server."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

DEFAULT_DATA_DIR = Path(os.environ.get("GRAMHUB_DATA_DIR", "server/data"))

# Repositories that are always part of the catalog, whatever the search returns.
# These are well-known open-source clients; the list is the seed, not the limit -
# discovery also searches GitHub for anything else that looks like a client.
DEFAULT_SEED_REPOS: tuple[str, ...] = (
    "Nekogram/Nekogram",
    "Telegram-FOSS-Team/Telegram-FOSS",
    "teidesu/inugram",
)

# Package names owned by the official Telegram client. An APK that claims one of
# these names is only acceptable when its signer is in TRUSTED_SIGNERS below;
# anything else is a repackaged build impersonating the official app.
OFFICIAL_PACKAGES: frozenset[str] = frozenset(
    {
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.messenger.beta",
        "org.telegram.plus",
    }
)

# SHA-256 fingerprints of signing certificates that are accepted for the packages
# in OFFICIAL_PACKAGES, as lowercase hex without separators.
#
# Deliberately empty: the real fingerprints have to be read off a build you
# obtained through a channel you trust, and hardcoding a guessed value here would
# turn the impersonation check into a rubber stamp. Populate it from
# `apksigner verify --print-certs` on the official APK, e.g.
#
#   GRAMHUB_TRUSTED_SIGNERS=org.telegram.messenger=abcdef0123...
OFFICIAL_SIGNERS: dict[str, set[str]] = {}

# Package names or signer fingerprints that must never be offered for install.
DENYLIST_PACKAGES: set[str] = set()
DENYLIST_SIGNERS: set[str] = set()

# Permissions that make sense in a messenger and therefore do not, on their own,
# raise the verdict. Anything outside this set is reported for the user to judge.
EXPECTED_PERMISSIONS: frozenset[str] = frozenset(
    {
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.MODIFY_AUDIO_SETTINGS",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_PHONE_NUMBERS",
        "android.permission.CALL_PHONE",
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        "android.permission.VIBRATE",
        "android.permission.WAKE_LOCK",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        "android.permission.FOREGROUND_SERVICE_CAMERA",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.SCHEDULE_EXACT_ALARM",
        "android.permission.USE_BIOMETRIC",
        "android.permission.USE_FINGERPRINT",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.AUTHENTICATE_ACCOUNTS",
        "android.permission.GET_ACCOUNTS",
        "android.permission.MANAGE_ACCOUNTS",
        "android.permission.USE_CREDENTIALS",
        "android.permission.BLUETOOTH",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.NFC",
        "android.permission.SET_WALLPAPER",
        "android.permission.DISABLE_KEYGUARD",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        "android.permission.WRITE_SETTINGS",
        "android.permission.QUERY_ALL_PACKAGES",
        "android.permission.MANAGE_OWN_CALLS",
        "android.permission.ANSWER_PHONE_CALLS",
        "android.permission.FOREGROUND_SERVICE_PHONE_CALL",
    }
)

# Permissions that let an app read or send messages and call data, or bind to a
# privileged system service. Nothing a messenger legitimately needs appears
# here, so any of these makes the verdict DANGEROUS.
HIGH_RISK_PERMISSIONS: frozenset[str] = frozenset(
    {
        "android.permission.READ_SMS",
        "android.permission.SEND_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.RECEIVE_MMS",
        "android.permission.RECEIVE_WAP_PUSH",
        "android.permission.PROCESS_OUTGOING_CALLS",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.BIND_DEVICE_ADMIN",
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
        "android.permission.READ_PRIVILEGED_PHONE_STATE",
        "android.permission.INSTALL_PACKAGES",
        "android.permission.DELETE_PACKAGES",
        "android.permission.MOUNT_UNMOUNT_FILESYSTEMS",
        "android.permission.WRITE_SECURE_SETTINGS",
    }
)

# Permissions that deserve a second look but are ordinary for a chat app:
# REQUEST_INSTALL_PACKAGES powers in-app self-update, and SYSTEM_ALERT_WINDOW
# draws chat heads. Treating these as dangerous would mark every real Telegram
# fork as malicious, which trains the user to ignore the verdict.
SENSITIVE_PERMISSIONS: frozenset[str] = frozenset(
    {
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.SYSTEM_ALERT_WINDOW",
        # Messengers read the call log and the phone number to match contacts.
        # Both need an explicit runtime grant, so they are worth reporting but do
        # not by themselves mean the APK is hostile.
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_PHONE_NUMBERS",
    }
)


@dataclass(frozen=True)
class Settings:
    """Everything the server reads from the environment, resolved once."""

    data_dir: Path = DEFAULT_DATA_DIR
    github_token: str | None = None
    seed_repos: tuple[str, ...] = DEFAULT_SEED_REPOS
    search_enabled: bool = True
    max_releases_per_repo: int = 3
    http_timeout: float = 60.0
    scanner_version: str = "1.2.0"
    android_home: Path | None = None
    official_packages: frozenset[str] = OFFICIAL_PACKAGES
    official_signers: dict[str, set[str]] | None = None
    denylist_packages: set[str] | None = None
    denylist_signers: set[str] | None = None
    # Set to a positive number to accept development-only signing certificates.
    allow_debug_signed: bool = False

    @property
    def database_path(self) -> Path:
        return self.data_dir / "catalog.sqlite3"

    @property
    def apk_cache_dir(self) -> Path:
        return self.data_dir / "apk-cache"

    @property
    def build_tools_dir(self) -> Path | None:
        """Newest installed build-tools directory, which holds apksigner/aapt2."""
        home = self.android_home or _default_android_home()
        if home is None:
            return None
        candidates = sorted(
            (home / "build-tools").glob("*"),
            key=lambda p: _version_key(p.name),
        )
        return candidates[-1] if candidates else None

    def ensure_dirs(self) -> None:
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.apk_cache_dir.mkdir(parents=True, exist_ok=True)

    def trusted_signers_for(self, package_name: str) -> set[str]:
        signers = dict(OFFICIAL_SIGNERS)
        signers.update(self.official_signers or {})
        return signers.get(package_name, set())

    def denied_packages(self) -> set[str]:
        return DENYLIST_PACKAGES | (self.denylist_packages or set())

    def denied_signers(self) -> set[str]:
        return DENYLIST_SIGNERS | (self.denylist_signers or set())

    def expected_permissions_snapshot(self) -> frozenset[str]:
        return EXPECTED_PERMISSIONS

    def high_risk_permissions_snapshot(self) -> frozenset[str]:
        return HIGH_RISK_PERMISSIONS

    def sensitive_permissions_snapshot(self) -> frozenset[str]:
        return SENSITIVE_PERMISSIONS


def _default_android_home() -> Path | None:
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(key)
        if value:
            return Path(value)
    guess = Path.home() / "android-sdk"
    return guess if guess.is_dir() else None


def _version_key(name: str) -> tuple[int, ...]:
    parts = []
    for chunk in name.split("."):
        parts.append(int(chunk) if chunk.isdigit() else -1)
    return tuple(parts)


def _parse_signer_map(raw: str) -> dict[str, set[str]]:
    """Parses `pkg=fingerprint[,fingerprint];pkg2=...` into a mapping."""
    mapping: dict[str, set[str]] = {}
    for entry in raw.split(";"):
        entry = entry.strip()
        if not entry or "=" not in entry:
            continue
        package, fingerprints = entry.split("=", 1)
        values = {f.strip().lower().replace(":", "") for f in fingerprints.split(",") if f.strip()}
        if values:
            mapping.setdefault(package.strip(), set()).update(values)
    return mapping


def _split_env(name: str) -> set[str]:
    raw = os.environ.get(name, "")
    return {item.strip() for item in raw.split(",") if item.strip()}


def load_settings(**overrides: object) -> Settings:
    """Builds Settings from the environment, then applies explicit overrides."""
    data_dir = Path(os.environ.get("GRAMHUB_DATA_DIR", str(DEFAULT_DATA_DIR)))
    seed_raw = os.environ.get("GRAMHUB_SEED_REPOS")
    seed_repos = (
        tuple(r.strip() for r in seed_raw.split(",") if r.strip())
        if seed_raw
        else DEFAULT_SEED_REPOS
    )

    settings = Settings(
        data_dir=data_dir,
        github_token=os.environ.get("GITHUB_TOKEN") or None,
        seed_repos=seed_repos,
        search_enabled=os.environ.get("GRAMHUB_SEARCH", "1") not in {"0", "false", "no"},
        max_releases_per_repo=int(os.environ.get("GRAMHUB_MAX_RELEASES", "3")),
        official_signers=_parse_signer_map(os.environ.get("GRAMHUB_TRUSTED_SIGNERS", "")),
        denylist_packages=_split_env("GRAMHUB_DENYLIST_PACKAGES"),
        denylist_signers={s.lower().replace(":", "") for s in _split_env("GRAMHUB_DENYLIST_SIGNERS")},
        allow_debug_signed=os.environ.get("GRAMHUB_ALLOW_DEBUG_SIGNED", "0") in {"1", "true", "yes"},
    )
    if overrides:
        settings = Settings(**{**settings.__dict__, **overrides})
    return settings
