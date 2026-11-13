"""Downloads release APKs to a local cache, safely.

Two properties matter here:

* the same file is never downloaded twice - the cache key is the URL, and the
  digest in the file name lets a scan result be reused;
* a download is written to a temporary file and only moved into place once it is
  complete, so a truncated transfer can never be scanned or served.
"""

from __future__ import annotations

import hashlib
import logging
import os
import re
import tempfile
from pathlib import Path

import httpx

from .config import Settings
from .models import ApkAsset

log = logging.getLogger(__name__)

# Hosts a release asset may come from. GitHub serves release downloads from
# github.com and then redirects to a content host; the exact host has changed
# over time (objects.githubusercontent.com, then
# release-assets.githubusercontent.com), so every host GitHub has been observed
# to use is listed. Anything else is refused rather than followed, which is what
# keeps a compromised release from redirecting the download to an attacker.
ALLOWED_HOSTS = (
    "github.com",
    "api.github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "github-releases.githubusercontent.com",
    "codeload.github.com",
)

MAX_DOWNLOAD_BYTES = 400 * 1024 * 1024


class DownloadError(RuntimeError):
    pass


class Downloader:
    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self.settings = settings
        self._transport = transport
        self._client: httpx.AsyncClient | None = None

    async def __aenter__(self) -> "Downloader":
        await self._ensure_client()
        return self

    async def __aexit__(self, *exc: object) -> None:
        await self.close()

    async def _ensure_client(self) -> httpx.AsyncClient:
        if self._client is None:
            headers = {"User-Agent": "GramHub-Catalog/1.0"}
            if self.settings.github_token:
                headers["Authorization"] = f"Bearer {self.settings.github_token}"
            self._client = httpx.AsyncClient(
                timeout=self.settings.http_timeout,
                follow_redirects=True,
                transport=self._transport,
                headers=headers,
            )
        return self._client

    async def close(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    def cache_path(self, asset: ApkAsset) -> Path:
        """Stable path for an asset, derived from its URL."""
        key = hashlib.sha256(asset.url.encode()).hexdigest()[:16]
        safe_name = "".join(c for c in asset.name if c.isalnum() or c in "._-")
        # Collapse "." runs so a name like "../../etc/passwd" cannot climb out of
        # the cache directory.
        safe_name = re.sub(r"\.+", ".", safe_name).strip(".")[:80]
        return self.settings.apk_cache_dir / f"{key}-{safe_name or 'apk'}"

    async def fetch(self, asset: ApkAsset) -> Path:
        """Returns a local path to the APK, downloading it if needed."""
        destination = self.cache_path(asset)
        if destination.exists() and destination.stat().st_size > 0:
            log.debug("cache hit for %s", asset.name)
            return destination

        _assert_allowed(asset.url)
        client = await self._ensure_client()
        self.settings.apk_cache_dir.mkdir(parents=True, exist_ok=True)

        handle = tempfile.NamedTemporaryFile(
            dir=self.settings.apk_cache_dir,
            prefix=".partial-",
            delete=False,
        )
        temp_path = Path(handle.name)
        written = 0
        try:
            async with client.stream("GET", asset.url) as response:
                if response.status_code >= 400:
                    raise DownloadError(
                        f"download failed with HTTP {response.status_code} for {asset.name}"
                    )
                _assert_allowed(str(response.url))
                async for chunk in response.aiter_bytes(1024 * 256):
                    written += len(chunk)
                    if written > MAX_DOWNLOAD_BYTES:
                        raise DownloadError(f"{asset.name} exceeds the size limit")
                    handle.write(chunk)
            handle.flush()
            os.fsync(handle.fileno())
        except httpx.HTTPError as exc:
            raise DownloadError(f"network error while fetching {asset.name}: {exc}") from exc
        finally:
            handle.close()

        if written == 0:
            temp_path.unlink(missing_ok=True)
            raise DownloadError(f"{asset.name} downloaded as an empty file")

        os.replace(temp_path, destination)
        return destination


def _assert_allowed(url: str) -> None:
    """Refuses to fetch from anywhere but GitHub's own hosts."""
    from urllib.parse import urlparse

    parsed = urlparse(url)
    if parsed.scheme != "https":
        raise DownloadError(f"refusing to download over {parsed.scheme or 'no'} scheme: {url}")
    host = (parsed.hostname or "").lower()
    if not any(host == allowed or host.endswith("." + allowed) for allowed in ALLOWED_HOSTS):
        raise DownloadError(f"refusing to download from untrusted host {host!r}")
