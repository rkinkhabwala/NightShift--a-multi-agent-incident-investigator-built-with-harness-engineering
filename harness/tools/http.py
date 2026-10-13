"""The only HTTP client tools may use. It enforces, in code, which endpoints are reachable
(spec §5.2 least privilege, §5.6 sandbox): GET requests to telemetry query APIs and the read-only
ops API. Target services, the chaos API and anything else are refused before any request is sent."""

from __future__ import annotations

from typing import Any
from urllib.parse import urlsplit

import httpx

from harness.config import EndpointsConfig

ALLOWED_PATHS: dict[str, tuple[str, ...]] = {
    "prometheus": ("/api/v1/query", "/api/v1/query_range", "/api/v1/series", "/api/v1/labels", "/api/v1/alerts"),
    "loki": ("/loki/api/v1/query", "/loki/api/v1/query_range", "/loki/api/v1/labels"),
    "tempo": ("/api/search", "/api/traces/"),
    "ops": ("/ops/deploys", "/ops/services"),
}


class ToolPermissionError(Exception):
    """A tool tried to reach something outside its allow-list."""


class TelemetryClient:
    def __init__(self, endpoints: EndpointsConfig, transport: httpx.AsyncBaseTransport | None = None,
                 timeout_s: float = 30.0):
        self.bases = {"prometheus": endpoints.prometheus, "loki": endpoints.loki,
                      "tempo": endpoints.tempo, "ops": endpoints.ops}
        self._client = httpx.AsyncClient(timeout=timeout_s, transport=transport)

    def url(self, backend: str, path: str) -> str:
        if backend not in ALLOWED_PATHS:
            raise ToolPermissionError(f"backend {backend!r} is not reachable from tools")
        clean = urlsplit(path).path
        if ".." in clean or not any(clean == p or clean.startswith(p.rstrip("/") + "/") or clean.startswith(p)
                                    for p in ALLOWED_PATHS[backend]):
            raise ToolPermissionError(f"path {clean!r} is not allowed on {backend}")
        if backend == "ops" and clean.startswith("/ops/services/") and clean.rstrip("/").endswith(("/restart", "/deploys")):
            raise ToolPermissionError(f"path {clean!r} is a write path")
        return self.bases[backend].rstrip("/") + clean

    async def get(self, backend: str, path: str, params: dict[str, Any] | None = None) -> Any:
        resp = await self._client.get(self.url(backend, path), params=params)
        if resp.status_code >= 400:
            raise httpx.HTTPStatusError(f"{backend} {resp.status_code}: {resp.text[:300]}",
                                        request=resp.request, response=resp)
        return resp.json()

    async def aclose(self) -> None:
        await self._client.aclose()
