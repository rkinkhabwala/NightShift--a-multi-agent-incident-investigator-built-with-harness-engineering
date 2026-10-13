"""Recorded model responses ("cassettes") so harness tests run without Ollama (spec §10 Phase 2).

A cassette is JSONL; each line is {"key", "request", "response"} where response is the raw
Ollama /api/chat JSON. RecordingAdapter wraps a live adapter and appends every exchange;
ReplayAdapter serves them back:
  * mode="exact": the request must hash to the recorded key (regression replay of live runs);
  * mode="sequence": responses are served in order (hand-written edge-case cassettes), with an
    optional per-entry "expect" block checked against the request.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any, Literal

from harness.models.base import ChatRequest, ModelAdapter, ModelResponse
from harness.models.ollama import parse_response


def request_key(req: ChatRequest) -> str:
    blob = json.dumps({"messages": req.messages, "tools": req.tools, "format": req.format,
                       "temperature": req.temperature, "think": req.think}, sort_keys=True, default=str)
    return hashlib.sha256(blob.encode()).hexdigest()[:16]


def _summary(req: ChatRequest) -> dict[str, Any]:
    return {"tools": [t["function"]["name"] for t in req.tools or []], "format": req.format is not None,
            "messages": len(req.messages), "last_role": req.messages[-1]["role"] if req.messages else None}


class CassetteMismatch(AssertionError):
    pass


class RecordingAdapter:
    def __init__(self, inner: ModelAdapter, path: str | Path):
        self.inner = inner
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)

    @property
    def model_id(self) -> str:
        return self.inner.model_id

    async def chat(self, request: ChatRequest) -> ModelResponse:
        response = await self.inner.chat(request)
        with self.path.open("a") as f:
            f.write(json.dumps({"key": request_key(request), "request": _summary(request), "response": response.raw}) + "\n")
        return response


class ReplayAdapter:
    def __init__(self, path: str | Path, mode: Literal["exact", "sequence"] = "sequence", model_id: str = "replay"):
        self.path = Path(path)
        self.mode = mode
        self._model_id = model_id
        self.entries = [json.loads(line) for line in self.path.read_text().splitlines() if line.strip()]
        self.position = 0
        self.requests: list[ChatRequest] = []

    @property
    def model_id(self) -> str:
        return self._model_id

    @property
    def exhausted(self) -> bool:
        return self.position >= len(self.entries)

    async def chat(self, request: ChatRequest) -> ModelResponse:
        self.requests.append(request)
        if self.mode == "exact":
            key = request_key(request)
            for entry in self.entries:
                if entry["key"] == key:
                    return parse_response(entry["response"], 0.0)
            raise CassetteMismatch(f"no recorded response for request {key} ({_summary(request)}) in {self.path.name}")
        if self.exhausted:
            raise CassetteMismatch(f"{self.path.name}: model called {self.position + 1} times, only {len(self.entries)} responses recorded")
        entry = self.entries[self.position]
        self.position += 1
        self._check_expectations(entry.get("expect") or {}, request)
        return parse_response(entry["response"], 0.0)

    def _check_expectations(self, expect: dict[str, Any], req: ChatRequest) -> None:
        got = _summary(req)
        n = self.position
        if "tools" in expect and sorted(expect["tools"]) != sorted(got["tools"]):
            raise CassetteMismatch(f"{self.path.name} #{n}: expected tools {expect['tools']}, got {got['tools']}")
        if "format" in expect and expect["format"] != got["format"]:
            raise CassetteMismatch(f"{self.path.name} #{n}: expected format={expect['format']}, got {got['format']}")
        if "last_role" in expect and expect["last_role"] != got["last_role"]:
            raise CassetteMismatch(f"{self.path.name} #{n}: expected last message role {expect['last_role']}, got {got['last_role']}")
        for needle in expect.get("last_contains", []):
            if needle not in str(req.messages[-1].get("content", "")):
                raise CassetteMismatch(f"{self.path.name} #{n}: last message does not contain {needle!r}")
