"""Application factory and REST endpoints.

Endpoints
---------
GET  /health                     liveness plus catalog/scan counters
GET  /clients                    the catalog; filters: q, package
GET  /clients/{id}               one entry
POST /clients/{id}/scan          download the latest APK and scan it
GET  /scans/{sha256}             a cached scan by APK digest

The server never returns an installable file itself: it hands out the GitHub
release URL plus the verdict, and the phone downloads from GitHub directly. That
keeps the server out of the data path and means a user can verify the same bytes
against the release digest independently.
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Query, Request

from .config import Settings, load_settings
from .downloader import DownloadError, Downloader
from .github import GitHubClient, discover, now_iso
from .models import CatalogEntry
from .scanner import SCANNER_VERSION, SecurityScanner
from .storage import Storage

log = logging.getLogger(__name__)


@asynccontextmanager
async def _lifespan(app: FastAPI):
    settings: Settings = app.state.settings
    settings.ensure_dirs()
    app.state.storage = Storage(settings.database_path)
    app.state.scanner = SecurityScanner(settings)
    app.state.downloader = Downloader(settings)
    yield
    await app.state.downloader.close()
    app.state.storage.close()


def create_app(settings: Settings | None = None, **overrides: object) -> FastAPI:
    resolved = settings or load_settings(**overrides)

    app = FastAPI(
        title="GramHub Catalog",
        version="1.0.0",
        summary="Discovers Telegram clients on GitHub and reports how safe their APKs are.",
        lifespan=_lifespan,
    )
    app.state.settings = resolved

    @app.get("/health")
    async def health(request: Request) -> dict[str, object]:
        storage: Storage = request.app.state.storage
        scanner: SecurityScanner = request.app.state.scanner
        return {
            "status": "ok",
            "entries": storage.count_entries(),
            "scans": storage.count_scans(),
            "last_refresh": storage.last_updated(),
            "scanner_version": SCANNER_VERSION,
            "android_tools": scanner.inspector.available,
            "github_token": bool(resolved.github_token),
            "search_enabled": resolved.search_enabled,
        }

    @app.get("/clients")
    async def list_clients(
        request: Request,
        q: str | None = Query(default=None, description="free-text filter"),
        package: str | None = Query(default=None, description="exact package name"),
        verdict: str | None = Query(default=None, description="only entries whose cached scan has this verdict"),
    ) -> dict[str, object]:
        storage: Storage = request.app.state.storage
        entries = storage.all_entries(package=package, query=q)

        payload = []
        for entry in entries:
            item = entry.to_dict()
            item["scan"] = _cached_scan_summary(storage, entry)
            payload.append(item)

        if verdict:
            payload = [i for i in payload if (i["scan"] or {}).get("verdict") == verdict]

        return {"count": len(payload), "clients": payload}

    @app.get("/clients/{entry_id}")
    async def get_client(request: Request, entry_id: str) -> dict[str, object]:
        storage: Storage = request.app.state.storage
        entry = storage.get_entry(entry_id)
        if entry is None:
            raise HTTPException(status_code=404, detail=f"unknown client {entry_id!r}")
        item = entry.to_dict()
        item["scan"] = _cached_scan_summary(storage, entry)
        return item

    @app.post("/clients/{entry_id}/scan")
    async def scan_client(request: Request, entry_id: str) -> dict[str, object]:
        storage: Storage = request.app.state.storage
        settings: Settings = request.app.state.settings
        entry = storage.get_entry(entry_id)
        if entry is None:
            raise HTTPException(status_code=404, detail=f"unknown client {entry_id!r}")

        asset = entry.latest_apk
        if asset is None:
            raise HTTPException(status_code=409, detail=f"{entry.repo} has no APK release")

        downloader: Downloader = request.app.state.downloader
        scanner: SecurityScanner = request.app.state.scanner

        try:
            path = await downloader.fetch(asset)
        except DownloadError as exc:
            raise HTTPException(status_code=502, detail=str(exc)) from exc

        cached = storage.get_scan(asset.sha256 or "", SCANNER_VERSION) if asset.sha256 else None
        if cached is not None:
            return {"client": entry.to_dict(), "scan": cached.to_dict(), "cached": True}

        result = scanner.scan(
            path,
            expected_sha256=asset.sha256,
            release_tag=entry.latest_version,
            official_package=None,
        )
        if result.sha256:
            storage.save_scan(result.sha256, result)
        return {"client": entry.to_dict(), "scan": result.to_dict(), "cached": False}

    @app.get("/scans/{digest}")
    async def get_scan(request: Request, digest: str) -> dict[str, object]:
        storage: Storage = request.app.state.storage
        result = storage.get_scan(digest.lower().removeprefix("sha256:"), SCANNER_VERSION)
        if result is None:
            raise HTTPException(status_code=404, detail="no cached scan for this digest")
        return result.to_dict()

    @app.post("/refresh")
    async def refresh(request: Request) -> dict[str, object]:
        """Re-runs discovery. Intended for a cron job rather than the phone."""
        settings: Settings = request.app.state.settings
        storage: Storage = request.app.state.storage
        async with GitHubClient(settings) as github:
            entries = await discover(settings, github)

        stamp = now_iso()
        for entry in entries:
            storage.upsert_entry(entry, stamp)
        return {
            "refreshed_at": stamp,
            "entries": len(entries),
            "total": storage.count_entries(),
        }

    return app


def _cached_scan_summary(storage: Storage, entry: CatalogEntry) -> dict[str, object] | None:
    """The verdict already known for an entry's latest asset, if any."""
    asset = entry.latest_apk
    if asset is None or not asset.sha256:
        return None
    result = storage.get_scan(asset.sha256, SCANNER_VERSION)
    if result is None:
        return None
    return {
        "verdict": result.verdict.value,
        "digest_verified": result.digest_verified,
        "debug_signed": result.debug_signed,
        "signers": result.signers,
        "unexpected_permissions": result.unexpected_permissions,
        "scanned_at": result.scanned_at,
        "findings": [f.to_dict() for f in result.findings],
    }


__all__ = ["create_app", "Verdict"]
