"""Scanner tests.

The fixtures are real APKs produced by the Android build tools, so the checks
run against genuine signature blocks and compiled manifests. Tests that need the
SDK are skipped when it is absent rather than silently passing.
"""

from __future__ import annotations

import shutil
from pathlib import Path

import pytest

from gramhub_server.config import Settings, load_settings
from gramhub_server.models import Verdict
from gramhub_server.scanner import SecurityScanner, sha256_file

from tests.apk_factory import build_apk

pytestmark = pytest.mark.usefixtures("sdk_available")


@pytest.fixture
def scanner(tmp_path: Path) -> SecurityScanner:
    return SecurityScanner(load_settings(data_dir=tmp_path / "data"))


@pytest.fixture(scope="session")
def benign_apk(tmp_path_factory: pytest.TempPathFactory, keystore: Path) -> Path:
    return build_apk(
        tmp_path_factory.mktemp("benign"),
        package="com.example.client",
        label="Example Client",
        permissions=["android.permission.INTERNET", "android.permission.CAMERA"],
        keystore=keystore,
    )


class TestHealthyApk:
    def test_verdict_is_trusted(self, scanner: SecurityScanner, benign_apk: Path):
        result = scanner.scan(benign_apk)
        assert result.verdict is Verdict.TRUSTED, result.findings

    def test_reads_manifest_fields(self, scanner: SecurityScanner, benign_apk: Path):
        result = scanner.scan(benign_apk)
        assert result.package_name == "com.example.client"
        assert result.version_name == "1.0.0"
        assert result.version_code == 1
        assert result.app_label == "Example Client"
        assert result.min_sdk == 24
        assert result.target_sdk == 34

    def test_records_the_signer(self, scanner: SecurityScanner, benign_apk: Path):
        result = scanner.scan(benign_apk)
        assert result.debug_signed is False
        assert len(result.signers) == 1
        assert len(result.signers[0]) == 64
        assert "example" in result.signer_subjects[0]

    def test_permissions_within_the_expected_set(self, scanner: SecurityScanner, benign_apk: Path):
        result = scanner.scan(benign_apk)
        assert result.unexpected_permissions == []
        assert "android.permission.CAMERA" in result.permissions

    def test_computes_the_digest(self, scanner: SecurityScanner, benign_apk: Path):
        result = scanner.scan(benign_apk)
        assert result.sha256 == sha256_file(benign_apk)
        assert result.file_size == benign_apk.stat().st_size


class TestDigestCheck:
    def test_matching_digest_is_reported_as_verified(self, scanner: SecurityScanner, benign_apk: Path):
        digest = sha256_file(benign_apk)
        result = scanner.scan(benign_apk, expected_sha256=digest)
        assert result.digest_verified is True
        assert result.verdict is Verdict.TRUSTED

    def test_mismatched_digest_is_dangerous(self, scanner: SecurityScanner, benign_apk: Path):
        result = scanner.scan(benign_apk, expected_sha256="00" * 32)
        assert result.digest_verified is False
        assert result.verdict is Verdict.DANGEROUS
        assert any(f.check == "digest" for f in result.findings)

    def test_missing_digest_does_not_lower_the_verdict(self, scanner: SecurityScanner, benign_apk: Path):
        result = scanner.scan(benign_apk)
        assert result.digest_verified is None
        assert result.verdict is Verdict.TRUSTED


class TestSignatureChecks:
    def test_unsigned_apk_is_rejected(
        self, scanner: SecurityScanner, tmp_path: Path, keystore: Path
    ):
        apk = build_apk(tmp_path, signed=False)
        result = scanner.scan(apk)
        assert result.verdict is Verdict.DANGEROUS
        assert any(f.check == "signature" for f in result.findings)

    def test_debug_signed_apk_is_rejected(
        self, scanner: SecurityScanner, tmp_path: Path, debug_keystore: Path
    ):
        apk = build_apk(tmp_path, keystore=debug_keystore)
        result = scanner.scan(apk)
        assert result.debug_signed is True
        assert result.verdict is Verdict.DANGEROUS
        assert any(f.check == "debug-signature" for f in result.findings)

    def test_debug_signed_apk_can_be_allowed_by_configuration(
        self, tmp_path: Path, debug_keystore: Path
    ):
        apk = build_apk(tmp_path, keystore=debug_keystore)
        lenient = SecurityScanner(load_settings(data_dir=tmp_path / "d", allow_debug_signed=True))
        result = lenient.scan(apk)
        assert result.debug_signed is True
        assert result.verdict is not Verdict.DANGEROUS

    def test_denylisted_signer_is_rejected(self, tmp_path: Path, keystore: Path):
        apk = build_apk(tmp_path, keystore=keystore)
        signer = SecurityScanner(load_settings(data_dir=tmp_path / "d")).scan(apk).signers[0]

        blocked = SecurityScanner(
            load_settings(data_dir=tmp_path / "d2", denylist_signers={signer})
        )
        result = blocked.scan(apk)
        assert result.verdict is Verdict.DANGEROUS
        assert any(f.check == "signer-denylist" for f in result.findings)


class TestImpersonationChecks:
    def test_official_package_from_unknown_signer_is_flagged(
        self, scanner: SecurityScanner, tmp_path: Path, keystore: Path
    ):
        apk = build_apk(tmp_path, package="org.telegram.messenger", keystore=keystore)
        result = scanner.scan(apk)
        assert any(f.check == "impersonation" for f in result.findings)
        assert result.verdict is Verdict.CAUTION

    def test_official_package_with_trusted_signer_is_accepted(
        self, tmp_path: Path, keystore: Path
    ):
        apk = build_apk(tmp_path, package="org.telegram.messenger", keystore=keystore)
        baseline = SecurityScanner(load_settings(data_dir=tmp_path / "d")).scan(apk)
        signer = baseline.signers[0]

        trusting = SecurityScanner(
            load_settings(
                data_dir=tmp_path / "d2",
                official_signers={"org.telegram.messenger": {signer}},
            )
        )
        result = trusting.scan(apk)
        impersonation = [f for f in result.findings if f.check == "impersonation"]
        assert impersonation and impersonation[0].severity.value == "info"
        assert result.verdict is Verdict.TRUSTED

    def test_official_package_from_a_wrong_trusted_signer_is_dangerous(
        self, tmp_path: Path, keystore: Path
    ):
        apk = build_apk(tmp_path, package="org.telegram.messenger", keystore=keystore)
        result = SecurityScanner(
            load_settings(
                data_dir=tmp_path / "d",
                official_signers={"org.telegram.messenger": {"ab" * 32}},
            )
        ).scan(apk)
        assert result.verdict is Verdict.DANGEROUS
        assert any(f.check == "impersonation" for f in result.findings)

    def test_denylisted_package_is_rejected(self, tmp_path: Path, keystore: Path):
        apk = build_apk(tmp_path, package="com.evil.client", keystore=keystore)
        result = SecurityScanner(
            load_settings(data_dir=tmp_path / "d", denylist_packages={"com.evil.client"})
        ).scan(apk)
        assert result.verdict is Verdict.DANGEROUS
        assert any(f.check == "package-denylist" for f in result.findings)

    def test_package_mismatch_against_catalog_is_flagged(
        self, scanner: SecurityScanner, tmp_path: Path, keystore: Path
    ):
        apk = build_apk(tmp_path, package="com.something.else", keystore=keystore)
        result = scanner.scan(apk, official_package="org.telegram.messenger")
        assert any(f.check == "package-mismatch" for f in result.findings)


class TestPermissionPolicy:
    def test_the_tiers_do_not_overlap(self):
        """A permission cannot be both expected and dangerous."""
        settings = Settings()
        high = settings.high_risk_permissions_snapshot()
        sensitive = settings.sensitive_permissions_snapshot()
        expected = settings.expected_permissions_snapshot()

        assert high & sensitive == set()
        # A permission we call "expected for a messenger" must not also be the
        # thing that makes a client look malicious, or every real client fails.
        assert high & expected == set()
        assert sensitive & expected == sensitive


class TestPermissionChecks:
    def test_sms_permissions_are_high_risk(self, scanner: SecurityScanner, tmp_path: Path, keystore: Path):
        apk = build_apk(
            tmp_path,
            permissions=["android.permission.INTERNET", "android.permission.READ_SMS"],
            keystore=keystore,
        )
        result = scanner.scan(apk)
        assert "android.permission.READ_SMS" in result.unexpected_permissions
        assert result.verdict is Verdict.DANGEROUS
        assert any(f.check == "high-risk-permissions" for f in result.findings)

    def test_install_packages_permission_is_sensitive_not_dangerous(
        self, scanner: SecurityScanner, tmp_path: Path, keystore: Path
    ):
        """Self-update must not make every real client look malicious."""
        apk = build_apk(
            tmp_path, permissions=["android.permission.REQUEST_INSTALL_PACKAGES"], keystore=keystore
        )
        result = scanner.scan(apk)
        assert any(f.check == "sensitive-permissions" for f in result.findings)
        assert not any(f.check == "high-risk-permissions" for f in result.findings)
        assert result.verdict is not Verdict.DANGEROUS

    def test_overlay_permission_is_sensitive_not_dangerous(
        self, scanner: SecurityScanner, tmp_path: Path, keystore: Path
    ):
        """Chat heads use SYSTEM_ALERT_WINDOW; that is normal for a messenger."""
        apk = build_apk(
            tmp_path, permissions=["android.permission.SYSTEM_ALERT_WINDOW"], keystore=keystore
        )
        result = scanner.scan(apk)
        assert any(f.check == "sensitive-permissions" for f in result.findings)
        assert result.verdict is not Verdict.DANGEROUS

    def test_call_log_and_phone_number_are_sensitive_not_dangerous(
        self, scanner: SecurityScanner, tmp_path: Path, keystore: Path
    ):
        """Every real client declares these; they need a runtime grant."""
        apk = build_apk(
            tmp_path,
            permissions=[
                "android.permission.READ_CALL_LOG",
                "android.permission.READ_PHONE_NUMBERS",
            ],
            keystore=keystore,
        )
        result = scanner.scan(apk)
        assert any(f.check == "sensitive-permissions" for f in result.findings)
        assert not any(f.check == "high-risk-permissions" for f in result.findings)
        assert result.verdict is not Verdict.DANGEROUS

    def test_binding_a_system_service_is_still_dangerous(
        self, scanner: SecurityScanner, tmp_path: Path, keystore: Path
    ):
        apk = build_apk(
            tmp_path,
            permissions=["android.permission.BIND_ACCESSIBILITY_SERVICE"],
            keystore=keystore,
        )
        result = scanner.scan(apk)
        assert result.verdict is Verdict.DANGEROUS
        assert any(f.check == "high-risk-permissions" for f in result.findings)

    def test_unusual_permission_is_reported_but_survivable(
        self, scanner: SecurityScanner, tmp_path: Path, keystore: Path
    ):
        apk = build_apk(
            tmp_path, permissions=["android.permission.BODY_SENSORS"], keystore=keystore
        )
        result = scanner.scan(apk)
        assert "android.permission.BODY_SENSORS" in result.unexpected_permissions
        assert result.verdict is Verdict.CAUTION


class TestVersionChecks:
    def test_downgrade_against_the_release_tag_is_flagged(
        self, scanner: SecurityScanner, tmp_path: Path, keystore: Path
    ):
        apk = build_apk(tmp_path, version_name="9.0.0", keystore=keystore)
        result = scanner.scan(apk, release_tag="v13.1.1")
        assert any(f.check == "version-downgrade" for f in result.findings)

    def test_matching_version_is_not_flagged(
        self, scanner: SecurityScanner, tmp_path: Path, keystore: Path
    ):
        apk = build_apk(tmp_path, version_name="13.1.1", keystore=keystore)
        result = scanner.scan(apk, release_tag="v13.1.1")
        assert not any(f.check == "version-downgrade" for f in result.findings)

    def test_abbreviated_version_is_not_a_downgrade(
        self, scanner: SecurityScanner, tmp_path: Path, keystore: Path
    ):
        apk = build_apk(tmp_path, version_name="13.1", keystore=keystore)
        result = scanner.scan(apk, release_tag="v13.1.1")
        assert not any(f.check == "version-downgrade" for f in result.findings)

    def test_newer_version_is_not_flagged(
        self, scanner: SecurityScanner, tmp_path: Path, keystore: Path
    ):
        apk = build_apk(tmp_path, version_name="13.2.0", keystore=keystore)
        result = scanner.scan(apk, release_tag="v13.1.1")
        assert not any(f.check == "version-downgrade" for f in result.findings)


class TestNonApkInput:
    def test_a_text_file_is_rejected(self, scanner: SecurityScanner, tmp_path: Path):
        bogus = tmp_path / "not-an-apk.apk"
        bogus.write_text("this is not an apk", encoding="utf-8")
        result = scanner.scan(bogus)
        assert result.verdict is Verdict.DANGEROUS
        assert result.error

    def test_a_missing_file_is_rejected(self, scanner: SecurityScanner, tmp_path: Path):
        result = scanner.scan(tmp_path / "absent.apk")
        assert result.verdict is Verdict.DANGEROUS
        assert result.error == "APK not found"

    def test_truncated_apk_is_rejected(self, scanner: SecurityScanner, benign_apk: Path, tmp_path: Path):
        truncated = tmp_path / "truncated.apk"
        truncated.write_bytes(benign_apk.read_bytes()[:512])
        result = scanner.scan(truncated)
        assert result.verdict is Verdict.DANGEROUS


class TestScannerAvailability:
    def test_reports_when_build_tools_are_missing(self, tmp_path: Path):
        """A deployment without the SDK must refuse to bless an APK."""
        settings = load_settings(data_dir=tmp_path, android_home=tmp_path / "no-sdk")
        scanner = SecurityScanner(settings)
        assert scanner.inspector.available is False

        apk = tmp_path / "fake.apk"
        apk.write_bytes(b"PK\x03\x04")
        result = scanner.scan(apk)
        assert result.verdict is Verdict.DANGEROUS
        assert "build tools" in (result.error or "").lower()

    def test_aapt2_and_apksigner_are_discovered(self, tmp_path: Path):
        if shutil.which("aapt2") is None and not Path.home().joinpath("android-sdk").is_dir():
            pytest.skip("no Android SDK on this machine")
        scanner = SecurityScanner(load_settings(data_dir=tmp_path))
        assert scanner.inspector.available is True
