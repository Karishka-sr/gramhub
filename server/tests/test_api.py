"""API tests.

Discovery is exercised against a fake GitHub, served through an httpx mock
transport, so the tests are deterministic and never touch the network. APK
downloads are likewise served from an in-process transport, and the bytes come
from real APKs built by the Android toolchain.
"""

from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from gramhub_server.api import create_app
from gramhub_server.config import load_settings
from gramhub_server.github import GitHubClient, discover
from gramhub_server.models import CatalogEntry, Release, ApkAsset
from gramhub_server.scanner import sha256_file
from gramhub_server.storage import Storage

from tests.apk_factory import build_apk


def repo_payload(**overrides) -> dict:
    base = {
        "full_name": "Nekogram/Nekogram",
        "name": "Nekogram",
        "description": "Open-source third-party Telegram client",
        "html_url": "https://github.com/Nekogram/Nekogram",
        "stargazers_count": 4015,
        "language": "Java",
        "fork": False,
        "archived": False,
        "topics": [],
        "default_branch": "master",
    }
    base.update(overrides)
    return base


def release_payload(tag: str = "v13.1.1", apk_name: str = "app-universal.apk", digest: str = "") -> dict:
    return {
        "tag_name": tag,
        "published_at": "2026-10-30T09:54:21Z",
        "prerelease": False,
        "html_url": f"https://github.com/Nekogram/Nekogram/releases/tag/{tag}",
        "assets": [
            {
                "name": apk_name,
                "browser_download_url": f"https://github.com/Nekogram/Nekogram/releases/download/{tag}/{apk_name}",
                "size": 1024,
                "digest": f"sha256:{digest}" if digest else None,
            }
        ],
    }


def github_transport(
    repos: dict[str, dict],
    releases: dict[str, list[dict]],
    search: list[dict] | None = None,
    files: dict[str, dict[str, str]] | None = None,
) -> httpx.MockTransport:
    """A fake GitHub: repos, releases, search results and file contents."""

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/search/repositories":
            return httpx.Response(200, json={"items": search or []})

        parts = [p for p in path.split("/") if p]
        if len(parts) >= 3 and parts[0] == "repos":
            repo = f"{parts[1]}/{parts[2]}"
            rest = parts[3:]
            if not rest:
                if repo in repos:
                    return httpx.Response(200, json=repos[repo])
                return httpx.Response(404, json={"message": "Not Found"})
            if rest[0] == "releases":
                return httpx.Response(200, json=releases.get(repo, []))
            if rest[0] == "contents":
                wanted = "/".join(rest[1:])
                content = (files or {}).get(repo, {}).get(wanted)
                if content is None:
                    return httpx.Response(404, json={"message": "Not Found"})
                import base64

                return httpx.Response(
                    200,
                    json={
                        "encoding": "base64",
                        "content": base64.b64encode(content.encode()).decode(),
                    },
                )
        return httpx.Response(404, json={"message": "Not Found"})

    return httpx.MockTransport(handler)


class TestDiscoveryAgainstFakeGitHub:
    async def test_builds_a_catalog_entry(self, tmp_path: Path):
        transport = github_transport(
            {"Nekogram/Nekogram": repo_payload()},
            {"Nekogram/Nekogram": [release_payload()]},
            files={"Nekogram/Nekogram": {"app/build.gradle.kts": 'applicationId = "tw.nekomimi.nekogram"'}},
        )
        settings = load_settings(data_dir=tmp_path, search_enabled=False, seed_repos=("Nekogram/Nekogram",))
        async with GitHubClient(settings, transport=transport) as github:
            entries = await discover(settings, github)

        assert len(entries) == 1
        entry = entries[0]
        assert entry.package_name == "tw.nekomimi.nekogram"
        assert entry.latest_version == "v13.1.1"
        assert entry.latest_apk.name == "app-universal.apk"

    async def test_search_results_are_merged_with_seeds(self, tmp_path: Path):
        transport = github_transport(
            {
                "Nekogram/Nekogram": repo_payload(),
                "teidesu/inugram": repo_payload(
                    full_name="teidesu/inugram", name="inugram",
                    description="a fork of Telegram for Android",
                ),
            },
            {
                "Nekogram/Nekogram": [release_payload()],
                "teidesu/inugram": [release_payload(tag="v1", apk_name="inugram-full.apk")],
            },
            search=[
                repo_payload(full_name="teidesu/inugram", name="inugram",
                             description="a fork of Telegram for Android"),
                repo_payload(full_name="someone/bot", name="telegram-bot",
                             description="A Telegram bot"),
            ],
        )
        settings = load_settings(data_dir=tmp_path, search_enabled=True, seed_repos=("Nekogram/Nekogram",))
        async with GitHubClient(settings, transport=transport) as github:
            entries = await discover(settings, github)

        repos = {e.repo for e in entries}
        assert repos == {"Nekogram/Nekogram", "teidesu/inugram"}

    async def test_a_searched_repo_without_apks_is_skipped(self, tmp_path: Path):
        transport = github_transport(
            {"someone/client": repo_payload(full_name="someone/client", name="client")},
            {"someone/client": [{"tag_name": "v1", "assets": []}]},
            search=[repo_payload(full_name="someone/client", name="client")],
        )
        settings = load_settings(data_dir=tmp_path, search_enabled=True, seed_repos=())
        async with GitHubClient(settings, transport=transport) as github:
            entries = await discover(settings, github)
        assert entries == []

    async def test_seed_repo_survives_without_releases(self, tmp_path: Path):
        transport = github_transport(
            {"Nekogram/Nekogram": repo_payload()},
            {"Nekogram/Nekogram": []},
        )
        settings = load_settings(data_dir=tmp_path, search_enabled=False, seed_repos=("Nekogram/Nekogram",))
        async with GitHubClient(settings, transport=transport) as github:
            entries = await discover(settings, github)
        # Kept as a pointer so the UI can still link to the project.
        assert len(entries) == 1
        assert entries[0].latest_apk is None

    async def test_rate_limit_is_survivable(self, tmp_path: Path):
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(403, text="API rate limit exceeded")

        settings = load_settings(data_dir=tmp_path, search_enabled=True, seed_repos=("Nekogram/Nekogram",))
        async with GitHubClient(settings, transport=httpx.MockTransport(handler)) as github:
            entries = await discover(settings, github)
        assert entries == []

    async def test_unreachable_github_is_survivable(self, tmp_path: Path):
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("no network", request=request)

        settings = load_settings(data_dir=tmp_path, search_enabled=True, seed_repos=("Nekogram/Nekogram",))
        async with GitHubClient(settings, transport=httpx.MockTransport(handler)) as github:
            entries = await discover(settings, github)
        assert entries == []


@pytest.fixture
def seeded_client(tmp_path: Path, keystore: Path) -> tuple[TestClient, Path, str]:
    """A TestClient whose storage already holds one scannable entry."""
    apk = build_apk(
        tmp_path / "build",
        package="tw.nekomimi.nekogram",
        label="Nekogram",
        version_name="13.1.1",
        permissions=["android.permission.INTERNET"],
        keystore=keystore,
    )
    digest = sha256_file(apk)
    url = "https://github.com/Nekogram/Nekogram/releases/download/v13.1.1/app-universal.apk"

    settings = load_settings(data_dir=tmp_path / "data")
    settings.ensure_dirs()
    storage = Storage(settings.database_path)
    storage.upsert_entry(
        CatalogEntry(
            id="nekogram-nekogram",
            package_name="tw.nekomimi.nekogram",
            label="Nekogram",
            repo="Nekogram/Nekogram",
            repo_url="https://github.com/Nekogram/Nekogram",
            description="Open-source third-party Telegram client",
            stars=4015,
            releases=[
                Release(
                    tag="v13.1.1",
                    published_at="2026-10-30T09:54:21Z",
                    prerelease=False,
                    html_url="https://github.com/Nekogram/Nekogram/releases/tag/v13.1.1",
                    assets=[ApkAsset(name="app-universal.apk", url=url, size=apk.stat().st_size, sha256=digest, abi="universal")],
                )
            ],
            latest_version="v13.1.1",
            latest_release_url="https://github.com/Nekogram/Nekogram/releases/tag/v13.1.1",
        ),
        "2026-11-13T00:00:00+00:00",
    )
    storage.close()

    app = create_app(settings)

    def apk_handler(request: httpx.Request) -> httpx.Response:
        if str(request.url) == url:
            return httpx.Response(200, content=apk.read_bytes(), headers={"content-type": "application/octet-stream"})
        return httpx.Response(404)

    transport = httpx.MockTransport(apk_handler)
    with TestClient(app) as client:
        client.app.state.downloader._transport = transport
        yield client, apk, digest


class TestApiEndpoints:
    def test_health(self, seeded_client):
        client, _, _ = seeded_client
        body = client.get("/health").json()
        assert body["status"] == "ok"
        assert body["entries"] == 1
        assert body["android_tools"] is True

    def test_lists_clients(self, seeded_client):
        client, _, _ = seeded_client
        body = client.get("/clients").json()
        assert body["count"] == 1
        entry = body["clients"][0]
        assert entry["label"] == "Nekogram"
        assert entry["latest_version"] == "v13.1.1"
        assert entry["scan"] is None

    def test_filters_by_query(self, seeded_client):
        client, _, _ = seeded_client
        assert client.get("/clients", params={"q": "neko"}).json()["count"] == 1
        assert client.get("/clients", params={"q": "nothing"}).json()["count"] == 0

    def test_filters_by_package(self, seeded_client):
        client, _, _ = seeded_client
        body = client.get("/clients", params={"package": "tw.nekomimi.nekogram"}).json()
        assert body["count"] == 1
        assert client.get("/clients", params={"package": "other.pkg"}).json()["count"] == 0

    def test_single_client_and_404(self, seeded_client):
        client, _, _ = seeded_client
        assert client.get("/clients/nekogram-nekogram").json()["label"] == "Nekogram"
        assert client.get("/clients/nope").status_code == 404

    def test_scan_downloads_verifies_and_caches(self, seeded_client):
        client, apk, digest = seeded_client

        first = client.post("/clients/nekogram-nekogram/scan")
        assert first.status_code == 200
        body = first.json()
        assert body["cached"] is False
        scan = body["scan"]
        assert scan["package_name"] == "tw.nekomimi.nekogram"
        assert scan["digest_verified"] is True
        assert scan["verdict"] == "trusted"
        assert scan["sha256"] == digest

        second = client.post("/clients/nekogram-nekogram/scan").json()
        assert second["cached"] is True

    def test_scan_result_is_reachable_by_digest(self, seeded_client):
        client, _, digest = seeded_client
        client.post("/clients/nekogram-nekogram/scan")
        body = client.get(f"/scans/{digest}").json()
        assert body["verdict"] == "trusted"
        assert client.get("/scans/" + "0" * 64).status_code == 404

    def test_cached_verdict_appears_in_the_listing(self, seeded_client):
        client, _, _ = seeded_client
        client.post("/clients/nekogram-nekogram/scan")
        entry = client.get("/clients").json()["clients"][0]
        assert entry["scan"]["verdict"] == "trusted"
        assert entry["scan"]["digest_verified"] is True

    def test_filtering_by_verdict(self, seeded_client):
        client, _, _ = seeded_client
        client.post("/clients/nekogram-nekogram/scan")
        assert client.get("/clients", params={"verdict": "trusted"}).json()["count"] == 1
        assert client.get("/clients", params={"verdict": "dangerous"}).json()["count"] == 0

    def test_scan_of_unknown_client_is_404(self, seeded_client):
        client, _, _ = seeded_client
        assert client.post("/clients/nope/scan").status_code == 404

    def test_scan_without_an_apk_release_is_409(self, tmp_path: Path):
        settings = load_settings(data_dir=tmp_path / "data")
        settings.ensure_dirs()
        storage = Storage(settings.database_path)
        storage.upsert_entry(
            CatalogEntry(
                id="no-release", package_name=None, label="NoRelease",
                repo="someone/no-release", repo_url="https://github.com/someone/no-release",
            ),
            "2026-11-13T00:00:00+00:00",
        )
        storage.close()
        with TestClient(create_app(settings)) as client:
            assert client.post("/clients/no-release/scan").status_code == 409

    def test_a_failed_download_is_reported_as_502(self, seeded_client):
        client, _, _ = seeded_client
        client.app.state.downloader._transport = httpx.MockTransport(
            lambda request: httpx.Response(500)
        )
        assert client.post("/clients/nekogram-nekogram/scan").status_code == 502


class TestDownloadSafety:
    def test_refuses_a_non_github_host(self, tmp_path: Path):
        from gramhub_server.downloader import DownloadError, _assert_allowed

        with pytest.raises(DownloadError):
            _assert_allowed("https://evil.example.com/malware.apk")
        with pytest.raises(DownloadError):
            _assert_allowed("http://github.com/insecure.apk")

    def test_allows_github_and_its_asset_hosts(self):
        from gramhub_server.downloader import _assert_allowed

        _assert_allowed("https://github.com/a/b/releases/download/v1/x.apk")
        _assert_allowed("https://objects.githubusercontent.com/asset")
        # GitHub moved release downloads to this host; rejecting it made every
        # scan fail with "refusing to download from untrusted host".
        _assert_allowed("https://release-assets.githubusercontent.com/asset?token=x")
        _assert_allowed("https://api.github.com/repos/a/b")

    def test_refuses_to_escape_the_cache_directory(self, tmp_path: Path):
        from gramhub_server.downloader import Downloader

        settings = load_settings(data_dir=tmp_path)
        downloader = Downloader(settings)
        asset = ApkAsset(name="../../etc/passwd", url="https://github.com/a/b/x.apk", size=1)
        path = downloader.cache_path(asset)
        assert path.parent == settings.apk_cache_dir
        assert ".." not in path.name


class TestStorageRoundTrip:
    def test_entries_and_scans_survive_a_reopen(self, tmp_path: Path):
        settings = load_settings(data_dir=tmp_path)
        settings.ensure_dirs()
        storage = Storage(settings.database_path)
        storage.upsert_entry(
            CatalogEntry(
                id="x-y", package_name="com.x.y", label="XY", repo="x/y",
                repo_url="https://github.com/x/y",
                releases=[
                    Release(
                        tag="v1", published_at=None, prerelease=False, html_url="h",
                        assets=[ApkAsset(name="a.apk", url="u", size=10, sha256="ab" * 32, abi="universal")],
                    )
                ],
                latest_version="v1",
            ),
            "2026-11-13T00:00:00+00:00",
        )
        storage.close()

        reopened = Storage(settings.database_path)
        entry = reopened.get_entry("x-y")
        assert entry is not None
        assert entry.package_name == "com.x.y"
        assert entry.releases[0].assets[0].sha256 == "ab" * 32
        assert entry.latest_apk.name == "a.apk"
        reopened.close()

    def test_upsert_updates_rather_than_duplicates(self, tmp_path: Path):
        settings = load_settings(data_dir=tmp_path)
        settings.ensure_dirs()
        storage = Storage(settings.database_path)
        for stars in (1, 2):
            storage.upsert_entry(
                CatalogEntry(
                    id="x-y", package_name=None, label="XY", repo="x/y",
                    repo_url="https://github.com/x/y", stars=stars,
                ),
                "2026-11-13T00:00:00+00:00",
            )
        assert storage.count_entries() == 1
        assert storage.get_entry("x-y").stars == 2
        storage.close()

    def test_a_scan_from_an_older_scanner_is_not_reused(self, tmp_path: Path):
        from gramhub_server.models import ScanResult, Verdict

        settings = load_settings(data_dir=tmp_path)
        settings.ensure_dirs()
        storage = Storage(settings.database_path)
        storage.save_scan(
            "cd" * 32,
            ScanResult(verdict=Verdict.TRUSTED, scanner_version="0.0.1-old"),
        )
        assert storage.get_scan("cd" * 32, "1.0.0") is None
        assert storage.get_scan("cd" * 32, "0.0.1-old") is not None
        storage.close()
