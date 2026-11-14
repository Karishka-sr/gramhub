#!/usr/bin/env python3
"""Re-discovers Telegram clients and rewrites the static catalog snapshot.

This is the job the `refresh-catalog` workflow runs on a schedule. It does in one
process what the server's `POST /refresh` endpoint plus `export_catalog.py` do
together: ask GitHub for Telegram client repositories, store them, then freeze the
result into `docs/clients.json`, which GitHub Pages serves to the app.

Usage:
    GITHUB_TOKEN=... python tools/refresh_catalog.py -o docs/clients.json
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))

from gramhub_server.config import load_settings  # noqa: E402
from gramhub_server.downloader import Downloader, DownloadError  # noqa: E402
from gramhub_server.github import GitHubClient, discover, now_iso  # noqa: E402
from gramhub_server.scanner import SCANNER_VERSION, SecurityScanner  # noqa: E402
from gramhub_server.storage import Storage  # noqa: E402

from export_catalog import _cached_scan  # noqa: E402


async def scan_entries(settings, storage: Storage, entries) -> tuple[int, int, dict[str, str]]:
    """Downloads and scans each entry's newest APK, caching by digest.

    The snapshot is the only place the app can read a verdict from: it talks to a
    static file, not to a running server, so nothing is scanned on demand. Without
    this step every entry would carry `scan: null` and the app's "Проверить"
    button would have nothing to show.

    Returns (scanned, failed, digests), where digests maps an entry id to the
    digest of the file that was actually scanned. That digest is needed because
    some releases publish no digest at all, so the entry's own asset has no key to
    look the scan up by.
    """
    scanner = SecurityScanner(settings)
    scanned = 0
    failed = 0
    digests: dict[str, str] = {}
    async with Downloader(settings) as downloader:
        for entry in entries:
            asset = entry.latest_apk
            if asset is None:
                continue
            if asset.sha256 and storage.get_scan(asset.sha256, SCANNER_VERSION) is not None:
                digests[entry.id] = asset.sha256
                continue
            try:
                path = await downloader.fetch(asset)
            except DownloadError as exc:
                failed += 1
                print(f"  {entry.id}: download failed ({exc})", file=sys.stderr)
                continue
            result = scanner.scan(
                path,
                expected_sha256=asset.sha256,
                release_tag=entry.latest_version,
                official_package=None,
            )
            if result.sha256:
                storage.save_scan(result.sha256, result)
                digests[entry.id] = result.sha256
            scanned += 1
            print(f"  {entry.id}: {result.verdict.value}")
    return scanned, failed, digests


async def refresh(db_path: Path, out: Path, scan: bool = True) -> int:
    settings = load_settings(data_dir=db_path.parent)
    storage = Storage(db_path)
    try:
        async with GitHubClient(settings) as github:
            entries = await discover(settings, github)

        stamp = now_iso()
        for entry in entries:
            storage.upsert_entry(entry, stamp)

        digests: dict[str, str] = {}
        if scan:
            print("scanning APKs (this is what fills the snapshot verdicts)")
            scanned, failed, digests = await scan_entries(settings, storage, storage.all_entries())
            print(f"scanned {scanned}, failed {failed}")

        clients = []
        for entry in storage.all_entries():
            item = entry.to_dict()
            item["scan"] = _cached_scan(storage, entry, digests.get(entry.id))
            clients.append(item)

        snapshot = {
            "count": len(clients),
            "scanner_version": SCANNER_VERSION,
            "generated_at": stamp,
            "clients": clients,
        }
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(snapshot, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print(f"discovered {len(entries)}, wrote {snapshot['count']} clients to {out}")
        return 0
    finally:
        storage.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", default=".catalog-cache", help="where to keep the working DB")
    parser.add_argument("-o", "--out", default="docs/clients.json", help="snapshot to write")
    parser.add_argument(
        "--no-scan",
        action="store_true",
        help="skip APK scanning and reuse whatever verdicts are already cached",
    )
    args = parser.parse_args()

    data_dir = Path(args.data_dir).expanduser().resolve()
    data_dir.mkdir(parents=True, exist_ok=True)
    return asyncio.run(
        refresh(
            data_dir / "catalog.sqlite3",
            Path(args.out).expanduser(),
            scan=not args.no_scan,
        )
    )


if __name__ == "__main__":
    raise SystemExit(main())
