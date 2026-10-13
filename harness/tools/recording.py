"""HTTP transports that record and replay telemetry responses, so tool tests and harness replays
run without the live stack. Requests are matched on path plus the non-time query parameters."""

from __future__ import annotations

import gzip
import json
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit

import httpx

TIME_PARAMS = {"start", "end", "time", "since", "step"}


def request_key(request: httpx.Request) -> str:
    url = urlsplit(str(request.url))
    params = sorted((k, v) for k, v in parse_qsl(url.query) if k not in TIME_PARAMS)
    return json.dumps([url.path, params])


class RecordingTransport(httpx.AsyncBaseTransport):
    def __init__(self, path: str | Path, inner: httpx.AsyncBaseTransport | None = None):
        self.path = Path(path)
        self.inner = inner or httpx.AsyncHTTPTransport()
        self.entries: dict[str, dict] = {}

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        response = await self.inner.handle_async_request(request)
        body = await response.aread()
        try:
            data = json.loads(body)
        except ValueError:
            data = body.decode(errors="replace")
        self.entries[request_key(request)] = {"status": response.status_code, "body": data}
        return httpx.Response(response.status_code, content=body, headers={"content-type": "application/json"})

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        blob = json.dumps(self.entries, sort_keys=True).encode()
        self.path.write_bytes(gzip.compress(blob, mtime=0) if self.path.suffix == ".gz" else blob)


class ReplayTransport(httpx.AsyncBaseTransport):
    def __init__(self, path: str | Path):
        raw = Path(path).read_bytes()
        self.entries = json.loads(gzip.decompress(raw) if str(path).endswith(".gz") else raw)
        self.misses: list[str] = []

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        key = request_key(request)
        entry = self.entries.get(key)
        if entry is None:
            self.misses.append(key)
            return httpx.Response(599, json={"error": f"no recorded response for {key}"})
        return httpx.Response(entry["status"], json=entry["body"])
