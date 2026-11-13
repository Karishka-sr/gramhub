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
from gramhub_server.github import GitHubClient, discover, now_iso  # noqa: E402
from gramhub_server.scanner import SCANNER_VERSION  # noqa: E402
from gramhub_server.storage import Storage  # noqa: E402

from export_catalog import _cached_scan  # noqa: E402


async def refresh(db_path: Path, out: Path) -> int:
    settings = load_settings(data_dir=db_path.parent)
    storage = Storage(db_path)
    try:
        async with GitHubClient(settings) as github:
            entries = await discover(settings, github)

        stamp = now_iso()
        for entry in entries:
            storage.upsert_entry(entry, stamp)

        clients = []
        for entry in storage.all_entries():
            item = entry.to_dict()
            item["scan"] = _cached_scan(storage, entry)
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
    args = parser.parse_args()

    data_dir = Path(args.data_dir).expanduser().resolve()
    data_dir.mkdir(parents=True, exist_ok=True)
    return asyncio.run(refresh(data_dir / "catalog.sqlite3", Path(args.out).expanduser()))


if __name__ == "__main__":
    raise SystemExit(main())
