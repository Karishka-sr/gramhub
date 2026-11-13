"""SQLite-backed catalog storage.

The schema is intentionally small: one row per catalog entry, one row per
release, and one row per scan keyed by the APK digest so a re-scan of the same
bytes is served from cache instead of downloading and shelling out again.
"""

from __future__ import annotations

import json
import sqlite3
from collections.abc import Iterable
from pathlib import Path

from .models import ApkAsset, CatalogEntry, Release, ScanResult, Verdict

SCHEMA = """
CREATE TABLE IF NOT EXISTS entries (
    id            TEXT PRIMARY KEY,
    package_name  TEXT,
    label         TEXT NOT NULL,
    repo          TEXT NOT NULL UNIQUE,
    repo_url      TEXT NOT NULL,
    description   TEXT NOT NULL DEFAULT '',
    stars         INTEGER NOT NULL DEFAULT 0,
    source        TEXT NOT NULL DEFAULT 'github',
    archived      INTEGER NOT NULL DEFAULT 0,
    latest_version TEXT,
    latest_release_url TEXT,
    updated_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS releases (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    entry_id    TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
    tag         TEXT NOT NULL,
    published_at TEXT,
    prerelease  INTEGER NOT NULL DEFAULT 0,
    html_url    TEXT NOT NULL,
    assets_json TEXT NOT NULL DEFAULT '[]',
    UNIQUE(entry_id, tag)
);

CREATE TABLE IF NOT EXISTS scans (
    digest          TEXT PRIMARY KEY,
    package_name    TEXT,
    version_name    TEXT,
    verdict         TEXT NOT NULL,
    result_json     TEXT NOT NULL,
    scanner_version TEXT NOT NULL,
    scanned_at      TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_entries_package ON entries(package_name);
CREATE INDEX IF NOT EXISTS idx_releases_entry ON releases(entry_id);
"""


class Storage:
    def __init__(self, path: Path) -> None:
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(path)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA foreign_keys = ON")
        self._conn.executescript(SCHEMA)
        self._conn.commit()

    def close(self) -> None:
        self._conn.close()

    # ---------------------------------------------------------------- entries

    def upsert_entry(self, entry: CatalogEntry, updated_at: str) -> None:
        with self._conn:
            self._conn.execute(
                """
                INSERT INTO entries (
                    id, package_name, label, repo, repo_url, description, stars,
                    source, archived, latest_version, latest_release_url, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(repo) DO UPDATE SET
                    id = excluded.id,
                    package_name = excluded.package_name,
                    label = excluded.label,
                    description = excluded.description,
                    stars = excluded.stars,
                    archived = excluded.archived,
                    latest_version = excluded.latest_version,
                    latest_release_url = excluded.latest_release_url,
                    updated_at = excluded.updated_at
                """,
                (
                    entry.id,
                    entry.package_name,
                    entry.label,
                    entry.repo,
                    entry.repo_url,
                    entry.description,
                    entry.stars,
                    entry.source,
                    int(entry.archived),
                    entry.latest_version,
                    entry.latest_release_url,
                    updated_at,
                ),
            )
            self._conn.execute("DELETE FROM releases WHERE entry_id = ?", (entry.id,))
            for release in entry.releases:
                self._conn.execute(
                    """
                    INSERT OR REPLACE INTO releases
                        (entry_id, tag, published_at, prerelease, html_url, assets_json)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    (
                        entry.id,
                        release.tag,
                        release.published_at,
                        int(release.prerelease),
                        release.html_url,
                        json.dumps([a.to_dict() for a in release.assets]),
                    ),
                )

    def all_entries(self, *, package: str | None = None, query: str | None = None) -> list[CatalogEntry]:
        sql = "SELECT * FROM entries"
        params: list[object] = []
        if package:
            sql += " WHERE package_name = ?"
            params.append(package)
        sql += " ORDER BY stars DESC, label COLLATE NOCASE ASC"
        rows = self._conn.execute(sql, params).fetchall()

        entries = [self._row_to_entry(row) for row in rows]
        if query:
            needle = query.strip().lower()
            entries = [
                e
                for e in entries
                if needle in e.label.lower()
                or needle in e.repo.lower()
                or needle in (e.package_name or "").lower()
                or needle in e.description.lower()
            ]
        return entries

    def get_entry(self, entry_id: str) -> CatalogEntry | None:
        row = self._conn.execute("SELECT * FROM entries WHERE id = ?", (entry_id,)).fetchone()
        if row is None:
            return None
        return self._row_to_entry(row)

    def delete_entries_not_in(self, repos: Iterable[str]) -> None:
        keep = list(repos)
        if not keep:
            return
        placeholders = ",".join("?" for _ in keep)
        with self._conn:
            self._conn.execute(
                f"DELETE FROM entries WHERE repo NOT IN ({placeholders})",  # noqa: S608 - placeholders only
                keep,
            )

    def count_entries(self) -> int:
        return int(self._conn.execute("SELECT COUNT(*) FROM entries").fetchone()[0])

    def last_updated(self) -> str | None:
        row = self._conn.execute("SELECT MAX(updated_at) FROM entries").fetchone()
        return row[0] if row else None

    # ------------------------------------------------------------------ scans

    def save_scan(self, digest: str, result: ScanResult) -> None:
        with self._conn:
            self._conn.execute(
                """
                INSERT INTO scans
                    (digest, package_name, version_name, verdict, result_json,
                     scanner_version, scanned_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(digest) DO UPDATE SET
                    package_name = excluded.package_name,
                    version_name = excluded.version_name,
                    verdict = excluded.verdict,
                    result_json = excluded.result_json,
                    scanner_version = excluded.scanner_version,
                    scanned_at = excluded.scanned_at
                """,
                (
                    digest,
                    result.package_name,
                    result.version_name,
                    result.verdict.value,
                    json.dumps(result.to_dict()),
                    result.scanner_version,
                    result.scanned_at,
                ),
            )

    def get_scan(self, digest: str, scanner_version: str) -> ScanResult | None:
        row = self._conn.execute(
            "SELECT result_json, scanner_version FROM scans WHERE digest = ?",
            (digest,),
        ).fetchone()
        if row is None:
            return None
        # A result produced by an older scanner is recomputed rather than trusted.
        if row["scanner_version"] != scanner_version:
            return None
        return _scan_from_dict(json.loads(row["result_json"]))

    def count_scans(self) -> int:
        return int(self._conn.execute("SELECT COUNT(*) FROM scans").fetchone()[0])

    # ------------------------------------------------------------------ helpers

    def _row_to_entry(self, row: sqlite3.Row) -> CatalogEntry:
        release_rows = self._conn.execute(
            "SELECT * FROM releases WHERE entry_id = ? ORDER BY published_at DESC",
            (row["id"],),
        ).fetchall()

        releases: list[Release] = []
        for rel in release_rows:
            assets = [
                ApkAsset(
                    name=a["name"],
                    url=a["url"],
                    size=a["size"],
                    sha256=a.get("sha256"),
                    abi=a.get("abi"),
                    split=bool(a.get("split", False)),
                )
                for a in json.loads(rel["assets_json"])
            ]
            releases.append(
                Release(
                    tag=rel["tag"],
                    published_at=rel["published_at"],
                    prerelease=bool(rel["prerelease"]),
                    html_url=rel["html_url"],
                    assets=assets,
                )
            )

        return CatalogEntry(
            id=row["id"],
            package_name=row["package_name"],
            label=row["label"],
            repo=row["repo"],
            repo_url=row["repo_url"],
            description=row["description"],
            stars=row["stars"],
            source=row["source"],
            archived=bool(row["archived"]),
            releases=releases,
            latest_version=row["latest_version"],
            latest_release_url=row["latest_release_url"],
        )


def _scan_from_dict(data: dict) -> ScanResult:
    from .models import Finding, Severity

    findings = [
        Finding(
            check=f["check"],
            severity=Severity(f["severity"]),
            summary=f["summary"],
            detail=f.get("detail", ""),
        )
        for f in data.get("findings", [])
    ]
    return ScanResult(
        verdict=Verdict(data["verdict"]),
        findings=findings,
        package_name=data.get("package_name"),
        version_name=data.get("version_name"),
        version_code=data.get("version_code"),
        app_label=data.get("app_label"),
        min_sdk=data.get("min_sdk"),
        target_sdk=data.get("target_sdk"),
        signers=data.get("signers", []),
        signer_subjects=data.get("signer_subjects", []),
        debug_signed=bool(data.get("debug_signed")),
        permissions=data.get("permissions", []),
        unexpected_permissions=data.get("unexpected_permissions", []),
        file_size=data.get("file_size", 0),
        sha256=data.get("sha256"),
        digest_verified=data.get("digest_verified"),
        expected_sha256=data.get("expected_sha256"),
        scanned_at=data.get("scanned_at", ""),
        scanner_version=data.get("scanner_version", ""),
        error=data.get("error"),
    )
