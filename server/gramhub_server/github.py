"""Finds Telegram clients on GitHub and turns them into catalog entries.

Two sources feed the catalog:

* a curated seed list of well-known open-source clients, which is always
  refreshed even when GitHub search is unavailable or rate-limited;
* a repository search, which is how a client published after this code was
  written still ends up in the catalog.

Only releases that actually ship an APK are kept, because the catalog exists to
hand out installable, scannable files.
"""

from __future__ import annotations

import hashlib
import logging
import re
from datetime import UTC, datetime

import httpx

from .config import Settings
from .models import ApkAsset, CatalogEntry, Release

log = logging.getLogger(__name__)

GITHUB_API = "https://api.github.com"

# Search queries, from the most specific to the loosest. Each one is a separate
# GitHub search call, so the list is kept short.
SEARCH_QUERIES = (
    "telegram client android in:name,description",
    "telegram fork android in:name,description",
    "nekogram in:name,description",
    "telegram-foss in:name,description",
)

# A repository is only considered a client when its name or description carries
# one of these, which keeps unrelated "telegram bot" projects out.
POSITIVE_MARKERS = ("telegram", "nekogram", "gram", "telegraph", "messenger")

# ...and not one of these, which is how bots, libraries and websites announce
# themselves.
# Matched as whole words. Word boundaries matter: a naive substring test for
# "cli" matches "client" and throws away every real client in the catalog.
NEGATIVE_WORDS = (
    "bot",
    "library",
    "sdk",
    "website",
    "homepage",
    "docs",
    "plugin",
    "cli",
    "server",
    "proxy",
    "mtproto",
    "theme",
    "sticker",
    "scraper",
    "parser",
    "bridge",
    "gateway",
    "downloader",
    "channel",
    "malware",
    "detector",
    "backup",
    "cve",
    "vulnerability",
    "audit",
    "instagram",
    "whatsapp",
    "imposter",
    "fake",
    "spy",
)

# Matched as substrings, for markers that are prefixes or hyphenated phrases.
NEGATIVE_PHRASES = (
    "proof-of",
    "tracking of",
    "mirror of",
    "archive of",
    "client for telegram bot",
    "analys",
    "reverse engine",
)

# Repository names that are never the client itself even when the description
# mentions Telegram.
NEGATIVE_NAME_SUFFIXES = ("-files", "-website", "-docs", "-backup", "-mirror", "-check")

ABI_PATTERNS: tuple[tuple[str, str], ...] = (
    ("arm64-v8a", "arm64-v8a"),
    ("arm64", "arm64-v8a"),
    ("aarch64", "arm64-v8a"),
    ("armeabi-v7a", "armeabi-v7a"),
    ("armeabi", "armeabi-v7a"),
    ("armv7", "armeabi-v7a"),
    ("x86_64", "x86_64"),
    ("x86-64", "x86_64"),
    ("amd64", "x86_64"),
    ("x86", "x86"),
    ("universal", "universal"),
)

DEBUG_PACKAGE_RE = re.compile(r"package_name\s*[=:]\s*[\"']([\w.]+)[\"']")


class GitHubClient:
    """Thin, testable wrapper over the GitHub REST endpoints we need."""

    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None) -> None:
        headers = {
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "GramHub-Catalog/1.0",
        }
        if settings.github_token:
            headers["Authorization"] = f"Bearer {settings.github_token}"
        self._client = httpx.AsyncClient(
            base_url=GITHUB_API,
            headers=headers,
            timeout=settings.http_timeout,
            transport=transport,
            follow_redirects=True,
        )
        self.settings = settings

    async def __aenter__(self) -> "GitHubClient":
        return self

    async def __aexit__(self, *exc: object) -> None:
        await self.close()

    async def close(self) -> None:
        await self._client.aclose()

    async def get_json(self, url: str, params: dict | None = None) -> object | None:
        try:
            response = await self._client.get(url, params=params)
        except httpx.HTTPError as exc:
            log.warning("github request failed: %s (%s)", url, exc)
            return None
        if response.status_code == 403 and "rate limit" in response.text.lower():
            log.warning("github rate limit hit for %s", url)
            return None
        if response.status_code >= 400:
            log.warning("github returned %s for %s", response.status_code, url)
            return None
        return response.json()

    async def search_repositories(self, query: str, per_page: int = 20) -> list[dict]:
        payload = await self.get_json(
            "/search/repositories",
            {"q": query, "sort": "stars", "order": "desc", "per_page": per_page},
        )
        if not isinstance(payload, dict):
            return []
        return [item for item in payload.get("items", []) if isinstance(item, dict)]

    async def repository(self, repo: str) -> dict | None:
        payload = await self.get_json(f"/repos/{repo}")
        return payload if isinstance(payload, dict) else None

    async def releases(self, repo: str, limit: int) -> list[dict]:
        payload = await self.get_json("/repos/" + repo + "/releases", {"per_page": limit})
        if not isinstance(payload, list):
            return []
        return [item for item in payload if isinstance(item, dict)]

    async def default_branch_file(self, repo: str, path: str) -> str | None:
        """Reads a text file from the default branch, used to find the package id."""
        payload = await self.get_json(f"/repos/{repo}/contents/{path}")
        if not isinstance(payload, dict) or payload.get("encoding") != "base64":
            return None
        import base64

        try:
            return base64.b64decode(payload.get("content", "")).decode("utf-8", "replace")
        except (ValueError, TypeError):
            return None


def classify_asset(name: str) -> tuple[str | None, bool]:
    """Returns the ABI a release asset targets and whether it is a split APK."""
    lowered = name.lower()
    if not lowered.endswith(".apk"):
        return None, False

    abi = None
    for needle, canonical in ABI_PATTERNS:
        if needle in lowered:
            abi = canonical
            break

    split = any(marker in lowered for marker in ("config.", "-split", "_split", ".split"))
    return abi, split


def looks_like_client(repo: dict) -> bool:
    """Decides whether a search result plausibly is a Telegram client."""
    name = (repo.get("name") or "").lower()
    description = (repo.get("description") or "").lower()
    topics = " ".join(repo.get("topics") or []).lower()
    haystack = f"{name} {description} {topics}"

    if not any(marker in haystack for marker in POSITIVE_MARKERS):
        return False
    # The optional trailing "s" catches plurals ("websites") without turning the
    # test back into a substring match ("clis" never appears inside "client").
    if any(re.search(rf"\b{re.escape(word)}s?\b", haystack) for word in NEGATIVE_WORDS):
        return False
    if any(phrase in haystack for phrase in NEGATIVE_PHRASES):
        return False
    if name.endswith(NEGATIVE_NAME_SUFFIXES):
        return False
    # The clients we care about are Android apps.
    if repo.get("language") not in (None, "Java", "Kotlin", "C", "C++", "Dart", "JavaScript"):
        return False
    return not repo.get("fork", False)


def repo_to_entry(
    repo: dict,
    releases: list[dict],
    *,
    package_name: str | None = None,
) -> CatalogEntry:
    """Builds a catalog entry out of a repository payload and its releases."""
    full_name = repo["full_name"]
    parsed: list[Release] = []

    for raw in releases:
        assets = []
        for asset in raw.get("assets") or []:
            name = asset.get("name") or ""
            abi, split = classify_asset(name)
            if abi is None and not name.lower().endswith(".apk"):
                continue
            digest = (asset.get("digest") or "").removeprefix("sha256:") or None
            assets.append(
                ApkAsset(
                    name=name,
                    url=asset.get("browser_download_url") or "",
                    size=int(asset.get("size") or 0),
                    sha256=digest,
                    abi=abi,
                    split=split,
                )
            )
        if not assets:
            continue
        parsed.append(
            Release(
                tag=raw.get("tag_name") or raw.get("name") or "",
                published_at=raw.get("published_at"),
                prerelease=bool(raw.get("prerelease")),
                html_url=raw.get("html_url") or "",
                assets=assets,
            )
        )

    latest = parsed[0] if parsed else None
    return CatalogEntry(
        id=_entry_id(full_name),
        package_name=package_name,
        label=repo.get("name") or full_name.split("/")[-1],
        repo=full_name,
        repo_url=repo.get("html_url") or f"https://github.com/{full_name}",
        description=(repo.get("description") or "").strip(),
        stars=int(repo.get("stargazers_count") or 0),
        source="github",
        archived=bool(repo.get("archived")),
        releases=parsed,
        latest_version=latest.tag if latest else None,
        latest_release_url=latest.html_url if latest else None,
    )


def extract_package_name(files: dict[str, str]) -> str | None:
    """Pulls an applicationId/package out of gradle or manifest snippets."""
    patterns = (
        re.compile(r'applicationId\s*[=(\s]\s*["\']([\w.]+)["\']'),
        re.compile(r'namespace\s*[=(\s]\s*["\']([\w.]+)["\']'),
        re.compile(r'\bpackage\s*=\s*["\']([\w.]+)["\']'),
        DEBUG_PACKAGE_RE,
    )
    for text in files.values():
        if not text:
            continue
        for pattern in patterns:
            match = pattern.search(text)
            if match:
                candidate = match.group(1)
                if candidate.count(".") >= 2:
                    return candidate
    return None


async def discover(settings: Settings, github: GitHubClient) -> list[CatalogEntry]:
    """Refreshes the whole catalog. Returns the entries that were kept."""
    candidates: dict[str, dict] = {}

    for repo in settings.seed_repos:
        payload = await github.repository(repo)
        if payload:
            candidates[payload["full_name"]] = payload
        else:
            log.warning("seed repo %s could not be fetched", repo)

    if settings.search_enabled:
        for query in SEARCH_QUERIES:
            for item in await github.search_repositories(query):
                if looks_like_client(item):
                    candidates.setdefault(item["full_name"], item)

    entries: list[CatalogEntry] = []
    for full_name, repo in candidates.items():
        releases = await github.releases(full_name, settings.max_releases_per_repo)
        package_name = await _resolve_package_name(github, repo)
        entry = repo_to_entry(repo, releases, package_name=package_name)
        if not entry.releases and entry.repo not in settings.seed_repos:
            # Nothing installable to offer, and it is not a seed we want to keep
            # around as a pointer either.
            log.info("skipping %s: no APK assets in its releases", full_name)
            continue
        entries.append(entry)

    entries.sort(key=lambda e: (-e.stars, e.label.lower()))
    return entries


async def _resolve_package_name(github: GitHubClient, repo: dict) -> str | None:
    """Best-effort package id lookup so two entries for one app can be spotted."""
    full_name = repo["full_name"]
    snippets: dict[str, str] = {}
    for path in (
        "app/build.gradle.kts",
        "app/build.gradle",
        "TMessagesProj_App/build.gradle",
        "TMessagesProj_App/build.gradle.kts",
        "build.gradle.kts",
        "build.gradle",
        "app/src/main/AndroidManifest.xml",
    ):
        text = await github.default_branch_file(full_name, path)
        if text:
            snippets[path] = text
        # Stop as soon as a package id is visible; each probe is an API call.
        if extract_package_name(snippets):
            break
    return extract_package_name(snippets)


def _entry_id(full_name: str) -> str:
    """A stable, URL-safe id derived from the repository name."""
    slug = re.sub(r"[^a-z0-9]+", "-", full_name.lower()).strip("-")
    if len(slug) <= 60:
        return slug
    digest = hashlib.sha256(full_name.encode()).hexdigest()[:8]
    return f"{slug[:50]}-{digest}"


def now_iso() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat()
