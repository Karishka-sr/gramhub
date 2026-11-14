#!/usr/bin/env python3
"""Exports the catalog database to a static JSON snapshot.

The GramHub app reads its "Not installed" tab from a catalog URL. Running the
FastAPI server permanently needs a host; instead this script freezes whatever the
server already discovered into a single file that can be committed to the
repository and served by GitHub Pages. The app then points at that file, so
clients keep appearing even when no server process is running.

Usage:
    python tools/export_catalog.py                     # reads GRAMHUB_DATA_DIR
    python tools/export_catalog.py --db path/to.db -o docs/clients.json
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))

from gramhub_server.config import load_settings  # noqa: E402
from gramhub_server.scanner import SCANNER_VERSION  # noqa: E402
from gramhub_server.storage import Storage  # noqa: E402


def build_snapshot(db_path: Path) -> dict[str, object]:
    """Reads every entry and its cached scan, and returns the app-facing payload."""
    storage = Storage(db_path)
    try:
        entries = storage.all_entries()
        clients = []
        for entry in entries:
            item = entry.to_dict()
            item["scan"] = _cached_scan(storage, entry)
            clients.append(item)
        return {
            "count": len(clients),
            "scanner_version": SCANNER_VERSION,
            "clients": clients,
        }
    finally:
        storage.close()


def _cached_scan(storage: Storage, entry, fallback_digest: str | None = None) -> dict[str, object] | None:
    """The verdict the server already holds for this entry's newest APK.

    The release's own digest is preferred, so a scan whose bytes did not match the
    release stays visible. [fallback_digest] is the digest the scanner computed for
    the file it actually read, which is the only key available for releases that
    publish no digest at all. The package name is deliberately not used as a key:
    many clients share `org.telegram.messenger.regular`, so it would attribute one
    repo's verdict to another.
    """
    asset = entry.latest_apk
    for key in (asset.sha256 if asset else None, fallback_digest):
        if not key:
            continue
        result = storage.get_scan(key, SCANNER_VERSION)
        if result is not None:
            summary = result.to_dict()
            summary["findings"] = [f.to_dict() for f in result.findings]
            return summary
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", help="path to gramhub.db; defaults to the configured data dir")
    parser.add_argument("-o", "--out", default="docs/clients.json", help="output file")
    args = parser.parse_args()

    db_path = Path(args.db).expanduser() if args.db else load_settings().database_path
    if not Path(db_path).exists():
        print(f"database not found: {db_path}", file=sys.stderr)
        return 1

    snapshot = build_snapshot(Path(db_path))
    out = Path(args.out).expanduser()
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(snapshot, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {snapshot['count']} clients to {out} ({out.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
