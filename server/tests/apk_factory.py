"""Builds small, real APKs with the Android build tools for scanner tests.

These are genuine APKs - compiled manifest, real signature block - not mocks.
The scanner shells out to `aapt2` and `apksigner`, so a hand-written ZIP would
not exercise the code paths that matter.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import zipfile
from pathlib import Path

import pytest

MANIFEST_TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{package}" android:versionCode="{version_code}" android:versionName="{version_name}">
{permissions}
  <application android:label="{label}" android:hasCode="true">
    <activity android:name="stub.Stub" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
      </intent-filter>
    </activity>
  </application>
</manifest>
"""

STUB_JAVA = """package stub;
import android.app.Activity;
import android.os.Bundle;
public class Stub extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) { super.onCreate(savedInstanceState); }
}
"""


def android_home() -> Path | None:
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(key):
            return Path(os.environ[key])
    guess = Path.home() / "android-sdk"
    return guess if guess.is_dir() else None


def newest_build_tools() -> Path | None:
    home = android_home()
    if home is None:
        return None
    candidates = sorted((home / "build-tools").glob("*"), key=lambda p: _vkey(p.name))
    return candidates[-1] if candidates else None


def platform_jar() -> Path | None:
    home = android_home()
    if home is None:
        return None
    platforms = sorted((home / "platforms").glob("android-*"), key=lambda p: _vkey(p.name.split("-")[1]))
    if not platforms:
        return None
    jar = platforms[-1] / "android.jar"
    return jar if jar.exists() else None


def _vkey(name: str) -> tuple[int, ...]:
    return tuple(int(p) if p.isdigit() else -1 for p in name.replace("-", ".").split("."))


def build_apk(
    out_dir: Path,
    *,
    package: str = "com.example.client",
    label: str = "Example Client",
    version_name: str = "1.0.0",
    version_code: int = 1,
    permissions: list[str] | None = None,
    keystore: Path | None = None,
    key_alias: str = "key",
    key_pass: str = "secret",
    signed: bool = True,
) -> Path:
    """Compiles, dexes, links and (optionally) signs a minimal APK."""
    build_tools = newest_build_tools()
    platform = platform_jar()
    if build_tools is None or platform is None:
        raise RuntimeError("Android SDK build tools or platform jar not found")

    out_dir.mkdir(parents=True, exist_ok=True)
    work = out_dir / "work"
    if work.exists():
        shutil.rmtree(work)
    (work / "res" / "mipmap").mkdir(parents=True)
    (work / "classes").mkdir(parents=True)
    (work / "dex").mkdir(parents=True)

    _write_png(work / "res" / "mipmap" / "ic_launcher.png")

    perm_xml = "\n".join(
        f'  <uses-permission android:name="{p}"/>' for p in (permissions or [])
    )
    (work / "AndroidManifest.xml").write_text(
        MANIFEST_TEMPLATE.format(
            package=package,
            version_code=version_code,
            version_name=version_name,
            label=label,
            permissions=perm_xml,
        ),
        encoding="utf-8",
    )
    (work / "Stub.java").write_text(STUB_JAVA, encoding="utf-8")

    _run(["javac", "-source", "8", "-target", "8", "-bootclasspath", str(platform),
          "-classpath", str(platform), "-d", str(work / "classes"), str(work / "Stub.java")])
    _run([str(build_tools / "d8"), "--lib", str(platform), "--min-api", "24",
          "--output", str(work / "dex"), str(work / "classes" / "stub" / "Stub.class")])
    _run([str(build_tools / "aapt2"), "compile", "--dir", str(work / "res"),
          "-o", str(work / "res.zip")])
    _run([str(build_tools / "aapt2"), "link", "-o", str(work / "unsigned.apk"),
          "-I", str(platform), "--manifest", str(work / "AndroidManifest.xml"),
          "-R", str(work / "res.zip"), "--min-sdk-version", "24",
          "--target-sdk-version", "34", "--version-code", str(version_code),
          "--version-name", version_name])

    # The dex has to be merged in manually: aapt2 links resources, not code.
    merged = work / "withdex.apk"
    with zipfile.ZipFile(work / "unsigned.apk") as zin, \
            zipfile.ZipFile(merged, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            zout.writestr(item, zin.read(item.filename))
        zout.write(work / "dex" / "classes.dex", "classes.dex")

    if not signed:
        target = out_dir / "unsigned.apk"
        shutil.copy(merged, target)
        return target

    _run([str(build_tools / "zipalign"), "-f", "4", str(merged), str(work / "aligned.apk")])
    target = out_dir / "signed.apk"
    _run([
        str(build_tools / "apksigner"), "sign",
        "--ks", str(keystore), "--ks-pass", f"pass:{key_pass}",
        "--key-pass", f"pass:{key_pass}", "--ks-key-alias", key_alias,
        "--out", str(target), str(work / "aligned.apk"),
    ])
    return target


def _run(command: list[str]) -> None:
    completed = subprocess.run(command, capture_output=True, text=True, check=False)  # noqa: S603
    if completed.returncode != 0:
        raise RuntimeError(
            f"command failed ({command[0]}): {completed.stdout}\n{completed.stderr}"
        )


def _write_png(path: Path) -> None:
    """A 4x4 solid-colour PNG, written by hand to avoid an image dependency."""
    import struct
    import zlib

    width = height = 4
    raw = b"".join(b"\x00" + bytes([42, 171, 238]) * width for _ in range(height))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )
    path.write_bytes(png)


@pytest.fixture(scope="session")
def keystore(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """A throwaway release keystore, generated once per test session."""
    path = tmp_path_factory.mktemp("keys") / "release.jks"
    _run([
        "keytool", "-genkeypair", "-keystore", str(path), "-storepass", "secret",
        "-keypass", "secret", "-alias", "key", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "3650", "-dname", "CN=Example Release, O=Example, C=US",
    ])
    return path


@pytest.fixture(scope="session")
def debug_keystore(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """A keystore whose subject matches the Android debug certificate."""
    path = tmp_path_factory.mktemp("keys") / "debug.jks"
    _run([
        "keytool", "-genkeypair", "-keystore", str(path), "-storepass", "secret",
        "-keypass", "secret", "-alias", "key", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "3650", "-dname", "CN=Android Debug, O=Android, C=US",
    ])
    return path


@pytest.fixture(scope="session")
def sdk_available() -> bool:
    return newest_build_tools() is not None and platform_jar() is not None
