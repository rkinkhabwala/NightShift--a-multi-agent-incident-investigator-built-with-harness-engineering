import json

import httpx
import pytest

from harness import config
from harness.models.base import ChatRequest, ModelError, assistant_message, tool_message
from harness.models.ollama import OllamaAdapter
from harness.models.recorded import CassetteMismatch, RecordingAdapter, ReplayAdapter

CFG = config.load().model

OK_BODY = {
    "model": "qwen3:8b", "done": True, "done_reason": "stop",
    "message": {"role": "assistant", "content": "", "tool_calls": [
        {"type": "function", "function": {"index": 0, "name": "query_logs", "arguments": {"service": "payments"}}}]},
    "prompt_eval_count": 812, "eval_count": 41, "total_duration": 2_500_000_000, "eval_duration": 1_000_000_000,
}


def adapter(handler):
    return OllamaAdapter(CFG, transport=httpx.MockTransport(handler), backoff_base_s=0)


async def test_request_carries_explicit_context_seed_keepalive_and_tools():
    seen = {}

    def handler(request):
        seen.update(json.loads(request.content))
        return httpx.Response(200, json=OK_BODY)

    tools = [{"type": "function", "function": {"name": "query_logs", "description": "d", "parameters": {"type": "object"}}}]
    resp = await adapter(handler).chat(ChatRequest(messages=[{"role": "user", "content": "hi"}], tools=tools, temperature=0.1))
    assert seen["options"] == {"num_ctx": 16384, "temperature": 0.1, "seed": 42}
    assert seen["keep_alive"] == "30m" and seen["stream"] is False and seen["think"] is False
    assert seen["tools"] == tools and "format" not in seen
    assert resp.tool_calls[0].name == "query_logs" and resp.tool_calls[0].arguments == {"service": "payments"}
    assert resp.usage.prompt_tokens == 812 and resp.usage.completion_tokens == 41 and resp.usage.total_ms == 2500


async def test_retries_transient_errors_then_succeeds():
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        if calls["n"] == 1:
            raise httpx.ConnectError("refused")
        if calls["n"] == 2:
            return httpx.Response(503, text="loading model")
        return httpx.Response(200, json=OK_BODY)

    resp = await adapter(handler).chat(ChatRequest(messages=[{"role": "user", "content": "hi"}]))
    assert calls["n"] == 3 and resp.tool_calls


async def test_client_errors_are_not_retried():
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        return httpx.Response(400, text="bad tool schema")

    with pytest.raises(ModelError) as e:
        await adapter(handler).chat(ChatRequest(messages=[{"role": "user", "content": "hi"}]))
    assert calls["n"] == 1 and not e.value.retryable and e.value.status == 400


async def test_gives_up_after_max_retries():
    def handler(request):
        return httpx.Response(500, text="boom")

    with pytest.raises(ModelError):
        await adapter(handler).chat(ChatRequest(messages=[{"role": "user", "content": "hi"}]))


async def test_string_arguments_are_parsed():
    body = json.loads(json.dumps(OK_BODY))
    body["message"]["tool_calls"][0]["function"]["arguments"] = '{"service": "orders"}'
    resp = await adapter(lambda r: httpx.Response(200, json=body)).chat(ChatRequest(messages=[]))
    assert resp.tool_calls[0].arguments == {"service": "orders"}


async def test_record_then_replay_exact(tmp_path):
    cassette = tmp_path / "c.jsonl"
    live = adapter(lambda r: httpx.Response(200, json=OK_BODY))
    req = ChatRequest(messages=[{"role": "user", "content": "alert"}])
    recorded = await RecordingAdapter(live, cassette).chat(req)
    replay = ReplayAdapter(cassette, mode="exact")
    again = await replay.chat(req)
    assert again.tool_calls == recorded.tool_calls
    with pytest.raises(CassetteMismatch):
        await replay.chat(ChatRequest(messages=[{"role": "user", "content": "different"}]))


async def test_sequence_replay_checks_expectations_and_exhaustion(tmp_path):
    cassette = tmp_path / "s.jsonl"
    cassette.write_text(json.dumps({"expect": {"tools": ["query_logs"], "last_role": "user"}, "response": OK_BODY}) + "\n")
    replay = ReplayAdapter(cassette)
    tools = [{"type": "function", "function": {"name": "query_logs", "parameters": {}}}]
    await replay.chat(ChatRequest(messages=[{"role": "user", "content": "x"}], tools=tools))
    assert replay.exhausted
    with pytest.raises(CassetteMismatch):
        await replay.chat(ChatRequest(messages=[{"role": "user", "content": "x"}], tools=tools))

    replay2 = ReplayAdapter(cassette)
    with pytest.raises(CassetteMismatch):
        await replay2.chat(ChatRequest(messages=[{"role": "user", "content": "x"}], tools=[]))


def test_message_helpers_use_ollama_shape():
    from harness.models.base import ModelResponse, ToolCall
    msg = assistant_message(ModelResponse(content="", tool_calls=[ToolCall("query_logs", {"q": 1})]))
    assert msg["tool_calls"][0]["function"] == {"name": "query_logs", "arguments": {"q": 1}}
    assert tool_message("query_logs", "ok") == {"role": "tool", "tool_name": "query_logs", "content": "ok"}
