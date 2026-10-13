"""Thin adapter over Ollama's native /api/chat (non-streaming) with tool calling."""

from __future__ import annotations

import asyncio
import json
import time
from typing import Any

import httpx

from harness.config import ModelConfig
from harness.models.base import ChatRequest, ModelError, ModelResponse, ToolCall, Usage

RETRYABLE_STATUS = {408, 429, 500, 502, 503, 504}


def build_payload(cfg: ModelConfig, req: ChatRequest) -> dict[str, Any]:
    options: dict[str, Any] = {"num_ctx": cfg.num_ctx, "temperature": req.temperature}
    if cfg.seed is not None:
        options["seed"] = cfg.seed
    if req.num_predict is not None:
        options["num_predict"] = req.num_predict
    payload: dict[str, Any] = {
        "model": cfg.name,
        "messages": req.messages,
        "stream": False,
        "keep_alive": cfg.keep_alive,
        "think": req.think,
        "options": options,
    }
    if req.tools:
        payload["tools"] = req.tools
    if req.format is not None:
        payload["format"] = req.format
    return payload


def parse_response(data: dict[str, Any], latency_ms: float) -> ModelResponse:
    msg = data.get("message") or {}
    calls = []
    for tc in msg.get("tool_calls") or []:
        fn = tc.get("function") or {}
        args = fn.get("arguments") or {}
        if isinstance(args, str):  # tolerate providers that send a JSON string
            try:
                args = json.loads(args)
            except json.JSONDecodeError:
                args = {"__unparseable__": args}
        calls.append(ToolCall(name=str(fn.get("name", "")), arguments=args if isinstance(args, dict) else {"__value__": args}))
    ns = 1e-6
    usage = Usage(
        prompt_tokens=int(data.get("prompt_eval_count") or 0),
        completion_tokens=int(data.get("eval_count") or 0),
        total_ms=(data.get("total_duration") or 0) * ns,
        load_ms=(data.get("load_duration") or 0) * ns,
        prompt_eval_ms=(data.get("prompt_eval_duration") or 0) * ns,
        eval_ms=(data.get("eval_duration") or 0) * ns,
    )
    return ModelResponse(content=msg.get("content") or "", thinking=msg.get("thinking") or "", tool_calls=calls,
                         done_reason=data.get("done_reason") or "stop", usage=usage, model=data.get("model", ""),
                         latency_ms=latency_ms, raw=data)


class OllamaAdapter:
    def __init__(self, cfg: ModelConfig, transport: httpx.AsyncBaseTransport | None = None,
                 backoff_base_s: float = 1.0):
        self.cfg = cfg
        self.backoff_base_s = backoff_base_s
        self._client = httpx.AsyncClient(base_url=cfg.base_url, timeout=cfg.request_timeout_s, transport=transport)

    @property
    def model_id(self) -> str:
        return f"ollama:{self.cfg.name}"

    async def chat(self, request: ChatRequest) -> ModelResponse:
        payload = build_payload(self.cfg, request)
        attempt = 0
        while True:
            start = time.perf_counter()
            try:
                resp = await self._client.post("/api/chat", json=payload)
                latency = (time.perf_counter() - start) * 1000
                if resp.status_code == 200:
                    return parse_response(resp.json(), latency)
                error = ModelError(f"ollama HTTP {resp.status_code}: {resp.text[:300]}",
                                   retryable=resp.status_code in RETRYABLE_STATUS, status=resp.status_code)
            except (httpx.TransportError, httpx.TimeoutException) as e:
                error = ModelError(f"ollama transport error: {e!r}", retryable=True)
            if not error.retryable or attempt >= self.cfg.max_retries:
                raise error
            await asyncio.sleep(self.backoff_base_s * (2 ** attempt))
            attempt += 1

    async def version(self) -> str:
        resp = await self._client.get("/api/version")
        resp.raise_for_status()
        return resp.json().get("version", "unknown")

    async def aclose(self) -> None:
        await self._client.aclose()
