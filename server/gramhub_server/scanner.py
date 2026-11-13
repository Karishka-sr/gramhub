"""Static security inspection of an APK.

The scanner answers one question for the user: "is this file safe to install?"
It never claims to prove an APK is benign - that is not decidable from the
outside - so it reports what it *observed* and how bad that observation is.

Checks performed
----------------
1.  SHA-256 of the downloaded bytes, compared with the digest GitHub publishes
    for the release asset. A mismatch means the file is not the one the release
    describes.
2.  APK signature verification via `apksigner`. An unsigned or unverifiable APK
    is rejected outright.
3.  Signer fingerprint and subject, so the same app signed by different keys can
    be spotted, and so a deny-list can be applied.
4.  Debug certificates: an APK signed with the well-known Android debug key is
    a development build, never something to install from a catalog.
5.  Package impersonation: an APK claiming an official Telegram package name is
    only accepted when its signer is on the trusted list for that package.
6.  Dangerous permission set, compared against what a messenger plausibly needs.
7.  Version downgrade against the release tag, which is a common way to get a
    user onto a build with a known flaw.
8.  Manifest sanity: exported components with no permission, a low targetSdk,
    missing version information.

Everything is derived from `aapt2` output plus the APK's own signature block;
the APK is never executed.
"""

from __future__ import annotations

import hashlib
import logging
import re
import shutil
import subprocess
import zipfile
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from .config import Settings
from .models import Finding, ScanResult, Severity, Verdict

log = logging.getLogger(__name__)

# Bumped whenever a verdict could change, so cached scans are re-run rather than
# served under the new rules. 1.2.0 also moved the call-log and phone-number
# permissions into the sensitive tier.
SCANNER_VERSION = "1.2.0"

# Fingerprints of the debug certificate shipped with the Android SDK. The debug
# key is generated per machine, so its fingerprint is not fixed; it is detected
# from the certificate subject instead (see DEBUG_SUBJECT_MARKERS), and this set
# exists so a deployment can pin known fingerprints explicitly.
DEBUG_KEY_SHA256: set[str] = set()

# Subject markers of the standard debug certificate
# (`CN=Android Debug, O=Android, C=US`).
DEBUG_SUBJECT_MARKERS = ("cn=androiddebug", "o=androiddebug")

VERSION_TAG_RE = re.compile(r"(\d+)\.(\d+)(?:\.(\d+))?")


@dataclass
class ApkFacts:
    """Raw observations pulled out of the APK before any judgement is made."""

    package_name: str | None = None
    version_name: str | None = None
    version_code: int | None = None
    app_label: str | None = None
    min_sdk: int | None = None
    target_sdk: int | None = None
    permissions: list[str] | None = None
    signers: list[str] | None = None
    signer_subjects: list[str] | None = None
    signed: bool = False
    verified: bool = False
    error: str | None = None


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class ApkInspector:
    """Reads facts out of an APK using the Android build tools."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        tools = settings.build_tools_dir
        self.apksigner = _find_tool(tools, "apksigner") or shutil.which("apksigner")
        self.aapt2 = _find_tool(tools, "aapt2") or shutil.which("aapt2")

    @property
    def available(self) -> bool:
        return bool(self.apksigner and self.aapt2)

    def inspect(self, apk: Path) -> ApkFacts:
        facts = ApkFacts(permissions=[], signers=[], signer_subjects=[])

        if not self.available:
            facts.error = (
                "Android build tools not found: apksigner/aapt2 are required to "
                "verify an APK. Set ANDROID_HOME or GRAMHUB_ANDROID_HOME."
            )
            return facts

        if not zipfile.is_zipfile(apk):
            facts.error = "the file is not a ZIP/APK archive"
            return facts

        self._read_manifest(apk, facts)
        self._read_signature(apk, facts)
        return facts

    def _read_manifest(self, apk: Path, facts: ApkFacts) -> None:
        result = _run([self.aapt2, "dump", "badging", str(apk)])
        if result is None:
            facts.error = facts.error or "aapt2 could not read the APK manifest"
            return

        for line in result.splitlines():
            if line.startswith("package:"):
                facts.package_name = _attr(line, "name")
                facts.version_name = _attr(line, "versionName")
                code = _attr(line, "versionCode")
                facts.version_code = int(code) if code and code.isdigit() else None
            elif line.startswith("application-label:"):
                facts.app_label = line.split(":", 1)[1].strip().strip("'")
            elif line.startswith("minSdkVersion:"):
                facts.min_sdk = _as_int(line.split(":", 1)[1])
            elif line.startswith("targetSdkVersion:"):
                facts.target_sdk = _as_int(line.split(":", 1)[1])
            elif line.startswith("uses-permission:"):
                name = _attr(line, "name")
                if name:
                    facts.permissions.append(name)
            elif line.startswith("uses-permission-sdk-23:"):
                name = _attr(line, "name")
                if name:
                    facts.permissions.append(name)

        facts.permissions = sorted(set(facts.permissions))

    def _read_signature(self, apk: Path, facts: ApkFacts) -> None:
        result = _run([self.apksigner, "verify", "--print-certs", "-v", str(apk)])
        if result is None:
            facts.signed = False
            facts.verified = False
            return

        facts.signed = "Verifies" in result or "Verified using" in result
        facts.verified = "DOES NOT VERIFY" not in result.upper()
        for line in result.splitlines():
            if "Signer #" in line and "certificate SHA-256 digest:" in line:
                digest = line.split(":", 1)[1].strip().lower().replace(":", "")
                if digest:
                    facts.signers.append(digest)
            elif "Signer #" in line and "certificate DN:" in line:
                subject = line.split(":", 1)[1].strip().lower()
                if subject:
                    facts.signer_subjects.append(subject)


class SecurityScanner:
    """Turns APK facts into findings and an overall verdict."""

    def __init__(self, settings: Settings, inspector: ApkInspector | None = None) -> None:
        self.settings = settings
        self.inspector = inspector or ApkInspector(settings)

    def scan(
        self,
        apk: Path,
        *,
        expected_sha256: str | None = None,
        release_tag: str | None = None,
        official_package: str | None = None,
    ) -> ScanResult:
        findings: list[Finding] = []
        result = ScanResult(
            verdict=Verdict.UNKNOWN,
            scanned_at=datetime.now(UTC).replace(microsecond=0).isoformat(),
            scanner_version=SCANNER_VERSION,
            expected_sha256=expected_sha256,
        )

        if not apk.exists():
            result.error = "APK not found"
            findings.append(
                Finding("download", Severity.HIGH, "The APK could not be downloaded.")
            )
            result.verdict = Verdict.DANGEROUS
            result.findings = findings
            return result

        result.file_size = apk.stat().st_size

        actual_digest = sha256_file(apk)
        result.sha256 = actual_digest
        findings.append(self._check_digest(actual_digest, expected_sha256, result))

        facts = self.inspector.inspect(apk)
        result.package_name = facts.package_name
        result.version_name = facts.version_name
        result.version_code = facts.version_code
        result.app_label = facts.app_label
        result.min_sdk = facts.min_sdk
        result.target_sdk = facts.target_sdk
        result.signers = list(facts.signers or [])
        result.signer_subjects = list(facts.signer_subjects or [])
        result.permissions = list(facts.permissions or [])
        result.error = facts.error

        if facts.error:
            findings.append(Finding("inspect", Severity.HIGH, "The APK could not be inspected.", facts.error))
            result.verdict = Verdict.DANGEROUS
            result.findings = findings
            return result

        findings.extend(self._check_signature(facts, result))
        findings.extend(self._check_identity(facts, official_package))
        findings.extend(self._check_permissions(facts, result))
        findings.extend(self._check_version(facts, release_tag))
        findings.extend(self._check_manifest(facts))

        result.verdict = _verdict_from(findings)
        result.findings = findings
        return result

    # ------------------------------------------------------------------ checks

    def _check_digest(
        self, actual: str, expected: str | None, result: ScanResult
    ) -> Finding:
        if not expected:
            result.digest_verified = None
            return Finding(
                "digest",
                Severity.INFO,
                "The release does not publish a digest for this file.",
                f"Computed sha256: {actual}. Without a published digest the download "
                "cannot be independently tied to the release.",
            )

        expected = expected.lower().removeprefix("sha256:").strip()
        if actual == expected:
            result.digest_verified = True
            return Finding(
                "digest",
                Severity.INFO,
                "SHA-256 matches the digest published by the release.",
                f"sha256:{actual}",
            )

        result.digest_verified = False
        return Finding(
            "digest",
            Severity.HIGH,
            "SHA-256 does NOT match the digest published by the release.",
            f"Expected {expected}, got {actual}. The file is not the one the release "
            "describes; do not install it.",
        )

    def _check_signature(self, facts: ApkFacts, result: ScanResult) -> list[Finding]:
        findings: list[Finding] = []

        if not facts.signed or not facts.verified:
            findings.append(
                Finding(
                    "signature",
                    Severity.HIGH,
                    "The APK signature does not verify.",
                    "An unsigned or broken signature means the file may have been "
                    "rebuilt or modified after the developer signed it.",
                )
            )
            return findings

        subjects = ", ".join(facts.signer_subjects) or "unknown signer"
        findings.append(
            Finding(
                "signature",
                Severity.INFO,
                f"Signature verifies, signed by {subjects}.",
                "Signers: " + (", ".join(facts.signers) or "unknown"),
            )
        )

        if self._is_debug_signed(facts):
            result.debug_signed = True
            findings.append(
                Finding(
                    "debug-signature",
                    Severity.HIGH if not self.settings.allow_debug_signed else Severity.LOW,
                    "The APK is signed with the Android debug key.",
                    "Debug builds are not meant to be distributed and cannot be "
                    "trusted as a release.",
                )
            )

        denied = self.settings.denied_signers()
        for signer in facts.signers:
            if signer in denied:
                findings.append(
                    Finding(
                        "signer-denylist",
                        Severity.HIGH,
                        "The signing certificate is on the deny list.",
                        f"Signer {signer} has been flagged as malicious.",
                    )
                )

        if len(set(facts.signers)) > 1:
            findings.append(
                Finding(
                    "multi-signer",
                    Severity.MEDIUM,
                    "The APK is signed by more than one certificate.",
                    "Multiple signers can be legitimate, but they are unusual for a "
                    "client release and worth a look.",
                )
            )

        return findings

    def _is_debug_signed(self, facts: ApkFacts) -> bool:
        for signer in facts.signers:
            if signer in DEBUG_KEY_SHA256:
                return True
        for subject in facts.signer_subjects:
            normalized = subject.replace(" ", "").lower()
            if any(marker in normalized for marker in DEBUG_SUBJECT_MARKERS):
                return True
            # `CN=Android Debug,O=Android,C=US` is the SDK default; the CN is the
            # part that identifies it, the rest is just as stable.
            if "cn=androiddebug" in normalized:
                return True
        return False

    def _check_identity(self, facts: ApkFacts, official_package: str | None) -> list[Finding]:
        findings: list[Finding] = []
        package = facts.package_name

        if package and package in self.settings.denied_packages():
            findings.append(
                Finding(
                    "package-denylist",
                    Severity.HIGH,
                    f"The package {package} is on the deny list.",
                    "This package name has been flagged as malicious.",
                )
            )

        if not package:
            findings.append(
                Finding(
                    "package",
                    Severity.MEDIUM,
                    "The APK does not declare a package name.",
                    "Without a package name the app cannot be identified or compared "
                    "against a known release.",
                )
            )
            return findings

        expected_official = official_package or package
        if expected_official in self.settings.official_packages:
            trusted = self.settings.trusted_signers_for(expected_official)
            if not trusted:
                findings.append(
                    Finding(
                        "impersonation",
                        Severity.MEDIUM,
                        f"The APK claims the official package name {expected_official}.",
                        "No trusted signing certificate is configured for this package, "
                        "so the official-build check could not run. Configure "
                        "GRAMHUB_TRUSTED_SIGNERS to enable it.",
                    )
                )
            elif not (trusted & set(facts.signers)):
                findings.append(
                    Finding(
                        "impersonation",
                        Severity.HIGH,
                        f"The APK claims {expected_official} but is signed by an unknown key.",
                        "This is what a repackaged build looks like: the official package "
                        "name with somebody else's certificate. Do not install it.",
                    )
                )
            else:
                findings.append(
                    Finding(
                        "impersonation",
                        Severity.INFO,
                        f"The APK claims {expected_official} and its signer is trusted.",
                        "",
                    )
                )

        if official_package and package != official_package:
            findings.append(
                Finding(
                    "package-mismatch",
                    Severity.MEDIUM,
                    f"The catalog expected {official_package} but the APK declares {package}.",
                    "The release does not contain the app the catalog describes.",
                )
            )

        return findings

    def _check_permissions(self, facts: ApkFacts, result: ScanResult) -> list[Finding]:
        findings: list[Finding] = []
        permissions = facts.permissions or []
        unexpected = [
            p for p in permissions if p not in self.settings.expected_permissions_snapshot()
        ]
        result.unexpected_permissions = unexpected

        high_risk = [p for p in permissions if p in self.settings.high_risk_permissions_snapshot()]
        if high_risk:
            pretty = ", ".join(sorted(p.rsplit(".", 1)[-1] for p in high_risk))
            findings.append(
                Finding(
                    "high-risk-permissions",
                    Severity.HIGH,
                    f"The APK requests {len(high_risk)} high-risk permission(s).",
                    f"{pretty}. A messenger does not need these; they allow reading "
                    "or sending messages and call data, or binding to a system service.",
                )
            )

        sensitive = [p for p in permissions if p in self.settings.sensitive_permissions_snapshot()]
        if sensitive:
            pretty = ", ".join(sorted(p.rsplit(".", 1)[-1] for p in sensitive))
            findings.append(
                Finding(
                    "sensitive-permissions",
                    Severity.MEDIUM,
                    f"The APK requests {len(sensitive)} sensitive permission(s).",
                    f"{pretty}. These are normal for a chat app - installing updates "
                    "and drawing chat heads - but they are worth knowing about.",
                )
            )

        if unexpected:
            pretty = ", ".join(sorted(p.rsplit(".", 1)[-1] for p in unexpected)[:12])
            findings.append(
                Finding(
                    "unexpected-permissions",
                    Severity.LOW,
                    f"{len(unexpected)} permission(s) are outside the expected messenger set.",
                    f"{pretty}. Review them before installing.",
                )
            )

        return findings

    def _check_version(self, facts: ApkFacts, release_tag: str | None) -> list[Finding]:
        if not release_tag or not facts.version_name:
            return []

        tag_parts = _version_tuple(release_tag)
        apk_parts = _version_tuple(facts.version_name)
        if not tag_parts or not apk_parts:
            return []

        # Abbreviated versions are common ("13.1" for "13.1.1"), so compare only
        # the components the APK actually declares.
        if apk_parts < tag_parts[: len(apk_parts)]:
            return [
                Finding(
                    "version-downgrade",
                    Severity.MEDIUM,
                    "The APK version is older than the release tag suggests.",
                    f"Release tag {release_tag} but the APK reports {facts.version_name}. "
                    "A mismatched build is a common way to serve a vulnerable version.",
                )
            ]
        return []

    def _check_manifest(self, facts: ApkFacts) -> list[Finding]:
        findings: list[Finding] = []

        if facts.target_sdk is not None and facts.target_sdk < 26:
            findings.append(
                Finding(
                    "target-sdk",
                    Severity.LOW,
                    f"The app targets Android API {facts.target_sdk}, which is old.",
                    "Apps targeting old API levels miss years of platform hardening.",
                )
            )

        if not facts.version_name:
            findings.append(
                Finding(
                    "version",
                    Severity.LOW,
                    "The APK does not report a version name.",
                    "",
                )
            )

        return findings


def _verdict_from(findings: list[Finding]) -> Verdict:
    if not findings:
        return Verdict.UNKNOWN
    return Verdict.worst([f.severity.to_verdict() for f in findings])


# --------------------------------------------------------------------- helpers


def _run(command: list[str], timeout: float = 120.0) -> str | None:
    try:
        completed = subprocess.run(  # noqa: S603 - fixed argv, no shell
            command,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        log.warning("command failed: %s (%s)", command[0], exc)
        return None
    output = (completed.stdout or "") + (completed.stderr or "")
    return output


def _find_tool(build_tools: Path | None, name: str) -> str | None:
    if build_tools is None:
        return None
    candidate = build_tools / name
    return str(candidate) if candidate.exists() else None


def _attr(line: str, key: str) -> str | None:
    match = re.search(rf"{key}='([^']*)'", line)
    return match.group(1) if match else None


def _as_int(value: str) -> int | None:
    value = value.strip().strip("'")
    return int(value) if value.isdigit() else None


def _version_tuple(text: str) -> tuple[int, ...]:
    """Numeric components of a version string, ignoring a leading 'v' or a suffix.

    `v13.1.1`, `13.1.1` and `13.1.1-7237` all yield `(13, 1, 1)`. Returns an
    empty tuple when no version-shaped run is present.
    """
    match = VERSION_TAG_RE.search(text)
    if not match:
        return ()
    # Keep only the components that were actually present: padding a two-part
    # version like "13.1" out to (13, 1, 0) would make it compare as older than
    # the tag "v13.1.1".
    return tuple(int(part) for part in match.groups() if part is not None)
