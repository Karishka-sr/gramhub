"""Discovery and catalog-construction tests. No network access."""

from __future__ import annotations

from gramhub_server.github import (
    classify_asset,
    extract_package_name,
    looks_like_client,
    repo_to_entry,
    _entry_id,
)


def repo(**overrides) -> dict:
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


class TestLooksLikeClient:
    def test_accepts_a_telegram_client(self):
        assert looks_like_client(repo())

    def test_rejects_a_bot(self):
        assert not looks_like_client(
            repo(name="telegram-bot", description="A Telegram bot for reminders")
        )

    def test_rejects_a_website_repository(self):
        assert not looks_like_client(
            repo(full_name="Nekogram/files", name="files",
                 description="Websites for Nekogram telegram client")
        )

    def test_rejects_a_malware_tracker(self):
        assert not looks_like_client(
            repo(full_name="XHUBERTH/NekoCheck", name="NekoCheck",
                 description="Tracking of malware embedded into Nekogram telegram client")
        )

    def test_rejects_a_backup_mirror(self):
        assert not looks_like_client(
            repo(full_name="FuaerCN/NekogramBackup", name="NekogramBackup",
                 description="Telegram client backup")
        )

    def test_rejects_an_unrelated_project(self):
        assert not looks_like_client(
            repo(name="notes", description="A simple note taking app")
        )

    def test_rejects_a_fork(self):
        assert not looks_like_client(repo(fork=True))

    def test_rejects_a_non_android_language(self):
        assert not looks_like_client(repo(language="Rust"))

    def test_rejects_an_apk_release_mirror(self):
        # A repo that only publishes someone else's APKs is not the client, and
        # offering it next to the real project invites installing an unmaintained
        # build.
        assert not looks_like_client(
            repo(full_name="someone/CherrygramBeta-APKs", name="CherrygramBeta-APKs",
                 description="APK files for Cherrygram Beta")
        )
        assert not looks_like_client(
            repo(full_name="someone/cherrygram-plus-releases", name="cherrygram-plus-releases",
                 description="Official Public Release Hub, Binary APKs for Cherrygram")
        )

    def test_accepts_a_brand_named_fork(self):
        # "Finegram" carries no "telegram" and no standalone "gram" word, which is
        # exactly the shape the search has to keep.
        assert looks_like_client(
            repo(full_name="Th3Nekit/Finegram", name="Finegram",
                 description="Клиент Telegram для Android на базе Cherrygram")
        )


class TestClassifyAsset:
    def test_universal_apk(self):
        assert classify_asset("Nekogram-13.1.1-universal.apk") == ("universal", False)

    def test_arm64_variants(self):
        assert classify_asset("app-arm64-v8a-release.apk") == ("arm64-v8a", False)
        assert classify_asset("app-aarch64.apk") == ("arm64-v8a", False)

    def test_armeabi(self):
        assert classify_asset("app-armeabi-v7a.apk") == ("armeabi-v7a", False)

    def test_x86_64(self):
        assert classify_asset("app-x86_64.apk") == ("x86_64", False)

    def test_asset_without_abi(self):
        assert classify_asset("app-release.apk") == (None, False)

    def test_split_apk_is_flagged(self):
        abi, split = classify_asset("app-arm64-v8a-config.xxhdpi.apk")
        assert abi == "arm64-v8a"
        assert split is True

    def test_non_apk_is_ignored(self):
        assert classify_asset("app-release.aab") == (None, False)


class TestRepoToEntry:
    def test_builds_entry_with_release_assets(self):
        releases = [
            {
                "tag_name": "v13.1.1",
                "published_at": "2026-10-30T09:54:21Z",
                "prerelease": False,
                "html_url": "https://github.com/Nekogram/Nekogram/releases/tag/v13.1.1",
                "assets": [
                    {
                        "name": "Nekogram-13.1.1-7237-universal.apk",
                        "browser_download_url": "https://github.com/x/y.apk",
                        "size": 73948009,
                        "digest": "sha256:53f23308e84e4331072416b198cc92eced1cb510694d73027bcc81b6c863767e",
                    },
                    {
                        "name": "TMessagesProj_App-play.aab",
                        "browser_download_url": "https://github.com/x/y.aab",
                        "size": 140404155,
                    },
                ],
            }
        ]
        entry = repo_to_entry(repo(), releases)

        assert entry.id == "nekogram-nekogram"
        assert entry.latest_version == "v13.1.1"
        assert entry.stars == 4015
        assert len(entry.releases) == 1
        # The .aab must not be offered as an APK.
        assert len(entry.releases[0].assets) == 1
        assert entry.releases[0].assets[0].abi == "universal"
        assert entry.releases[0].assets[0].sha256.startswith("53f23308")

    def test_drops_releases_without_apks(self):
        releases = [
            {
                "tag_name": "v1",
                "assets": [{"name": "source.zip", "browser_download_url": "u", "size": 1}],
            }
        ]
        entry = repo_to_entry(repo(), releases)
        assert entry.releases == []
        assert entry.latest_apk is None

    def test_latest_apk_prefers_universal_build(self):
        releases = [
            {
                "tag_name": "v1",
                "assets": [
                    {"name": "app-x86.apk", "browser_download_url": "x", "size": 1},
                    {"name": "app-universal.apk", "browser_download_url": "u", "size": 1},
                ],
            }
        ]
        entry = repo_to_entry(repo(), releases)
        assert entry.latest_apk.name == "app-universal.apk"

    def test_keeps_package_name_when_known(self):
        entry = repo_to_entry(repo(), [], package_name="org.telegram.messenger")
        assert entry.package_name == "org.telegram.messenger"


class TestExtractPackageName:
    def test_reads_application_id_from_kotlin_dsl(self):
        files = {"app/build.gradle.kts": 'applicationId = "tw.nekomimi.nekogram"'}
        assert extract_package_name(files) == "tw.nekomimi.nekogram"

    def test_reads_namespace(self):
        files = {"build.gradle": 'namespace "com.example.app"'}
        assert extract_package_name(files) == "com.example.app"

    def test_reads_manifest_package(self):
        files = {"AndroidManifest.xml": '<manifest package="org.telegram.messenger">'}
        assert extract_package_name(files) == "org.telegram.messenger"

    def test_ignores_a_bare_word(self):
        assert extract_package_name({"x": 'applicationId = "app"'}) is None

    def test_returns_none_for_empty_input(self):
        assert extract_package_name({}) is None


class TestEntryId:
    def test_slugifies_the_repository(self):
        assert _entry_id("Nekogram/Nekogram") == "nekogram-nekogram"

    def test_is_stable_for_long_names(self):
        long_name = "someone/" + "very-long-repository-name-" * 4
        first = _entry_id(long_name)
        assert first == _entry_id(long_name)
        assert len(first) <= 60
