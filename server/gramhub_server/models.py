"""Domain models shared by discovery, scanning and the REST layer."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum


class Verdict(str, Enum):
    """How safe an APK looks after inspection."""

    TRUSTED = "trusted"
    CAUTION = "caution"
    DANGEROUS = "dangerous"
    UNKNOWN = "unknown"

    @property
    def rank(self) -> int:
        return {"trusted": 0, "unknown": 1, "caution": 2, "dangerous": 3}[self.value]

    @classmethod
    def worst(cls, values: list["Verdict"]) -> "Verdict":
        if not values:
            return cls.UNKNOWN
        return max(values, key=lambda v: v.rank)


class Severity(str, Enum):
    INFO = "info"
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"

    @property
    def rank(self) -> int:
        return {"info": 0, "low": 1, "medium": 2, "high": 3}[self.value]

    def to_verdict(self) -> Verdict:
        return {
            Severity.INFO: Verdict.TRUSTED,
            Severity.LOW: Verdict.CAUTION,
            Severity.MEDIUM: Verdict.CAUTION,
            Severity.HIGH: Verdict.DANGEROUS,
        }[self]


@dataclass
class Finding:
    """A single observation from a scan."""

    check: str
    severity: Severity
    summary: str
    detail: str = ""

    def to_dict(self) -> dict[str, str]:
        return {
            "check": self.check,
            "severity": self.severity.value,
            "summary": self.summary,
            "detail": self.detail,
        }


@dataclass
class ApkAsset:
    """One downloadable APK attached to a GitHub release."""

    name: str
    url: str
    size: int
    sha256: str | None = None
    abi: str | None = None
    split: bool = False

    def to_dict(self) -> dict[str, object]:
        return {
            "name": self.name,
            "url": self.url,
            "size": self.size,
            "sha256": self.sha256,
            "abi": self.abi,
            "split": self.split,
        }


@dataclass
class Release:
    tag: str
    published_at: str | None
    prerelease: bool
    html_url: str
    assets: list[ApkAsset] = field(default_factory=list)

    def to_dict(self) -> dict[str, object]:
        return {
            "tag": self.tag,
            "published_at": self.published_at,
            "prerelease": self.prerelease,
            "html_url": self.html_url,
            "assets": [a.to_dict() for a in self.assets],
        }


@dataclass
class CatalogEntry:
    """A client that the catalog knows about, whether or not it is downloadable."""

    id: str
    package_name: str | None
    label: str
    repo: str
    repo_url: str
    description: str = ""
    stars: int = 0
    source: str = "github"
    archived: bool = False
    releases: list[Release] = field(default_factory=list)
    latest_version: str | None = None
    latest_release_url: str | None = None

    @property
    def latest_apk(self) -> ApkAsset | None:
        if not self.releases:
            return None
        for release in self.releases:
            for asset in release.assets:
                if asset.abi in {None, "universal"} and not asset.split:
                    return asset
        for release in self.releases:
            if release.assets:
                return release.assets[0]
        return None

    def to_dict(self) -> dict[str, object]:
        return {
            "id": self.id,
            "package_name": self.package_name,
            "label": self.label,
            "repo": self.repo,
            "repo_url": self.repo_url,
            "description": self.description,
            "stars": self.stars,
            "source": self.source,
            "archived": self.archived,
            "latest_version": self.latest_version,
            "latest_release_url": self.latest_release_url,
            "releases": [r.to_dict() for r in self.releases],
        }


@dataclass
class ScanResult:
    """Outcome of inspecting one APK."""

    verdict: Verdict
    findings: list[Finding] = field(default_factory=list)
    package_name: str | None = None
    version_name: str | None = None
    version_code: int | None = None
    app_label: str | None = None
    min_sdk: int | None = None
    target_sdk: int | None = None
    signers: list[str] = field(default_factory=list)
    signer_subjects: list[str] = field(default_factory=list)
    debug_signed: bool = False
    permissions: list[str] = field(default_factory=list)
    unexpected_permissions: list[str] = field(default_factory=list)
    file_size: int = 0
    sha256: str | None = None
    digest_verified: bool | None = None
    expected_sha256: str | None = None
    scanned_at: str = ""
    scanner_version: str = ""
    error: str | None = None

    def to_dict(self) -> dict[str, object]:
        return {
            "verdict": self.verdict.value,
            "findings": [f.to_dict() for f in self.findings],
            "package_name": self.package_name,
            "version_name": self.version_name,
            "version_code": self.version_code,
            "app_label": self.app_label,
            "min_sdk": self.min_sdk,
            "target_sdk": self.target_sdk,
            "signers": self.signers,
            "signer_subjects": self.signer_subjects,
            "debug_signed": self.debug_signed,
            "permissions": self.permissions,
            "unexpected_permissions": self.unexpected_permissions,
            "file_size": self.file_size,
            "sha256": self.sha256,
            "digest_verified": self.digest_verified,
            "expected_sha256": self.expected_sha256,
            "scanned_at": self.scanned_at,
            "scanner_version": self.scanner_version,
            "error": self.error,
        }
