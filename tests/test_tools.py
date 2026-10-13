import json

import pytest

from harness.tools.base import Permission, Tool, ToolInput, ToolOutput, ToolSet
from harness.tools.http import TelemetryClient, ToolPermissionError
from harness.tools.registry import READ_TOOLS, toolset


def payload(result):
    """The JSON inside the <untrusted_data> wrapper."""
    body = result.content.split("\n", 1)[1].rsplit("\n", 1)[0]
    return json.loads(body)


# --- allow-list -----------------------------------------------------------------------------------
def test_allow_list_blocks_everything_but_read_paths(settings):
    http = TelemetryClient(settings.endpoints)
    assert http.url("prometheus", "/api/v1/query").endswith("/api/v1/query")
    assert http.url("ops", "/ops/services/orders/flags")
    for backend, path in [("ops", "/chaos/scenarios/S01/inject"), ("ops", "/chaos/reset-all"), ("ops", "/ops/alerts"),
                          ("ops", "/ops/services/payments/restart"), ("ops", "/ops/services/payments/deploys"),
                          ("prometheus", "/api/v1/admin/tsdb/delete_series"), ("loki", "/loki/api/v1/push"),
                          ("ops", "/ops/deploys/../../chaos/reset-all"), ("orders", "/admin/flags")]:
        with pytest.raises(ToolPermissionError):
            http.url(backend, path)


# --- tool set rules -------------------------------------------------------------------------------
class _Write(Tool):
    name = "rollback_deploy"
    description = "x"
    permission = Permission.WRITE

    class Input(ToolInput):
        service: str

    async def run(self, args, ctx):
        return ToolOutput({}, None)


def test_toolset_enforces_least_privilege_and_size():
    with pytest.raises(ValueError):
        ToolSet("generalist", [_Write()])
    with pytest.raises(ValueError):
        ToolSet("too-many", list(READ_TOOLS.values())[:6])
    g = toolset("generalist")
    assert sorted(g.tools) == ["list_changes", "query_logs", "query_metrics", "query_traces", "read_runbook"]
    assert all(s["function"]["parameters"]["type"] == "object" for s in g.schemas())


async def test_unknown_write_and_invalid_calls_are_rejected_with_guidance(ctx_factory, board):
    ctx, _ = ctx_factory("baseline")
    g = toolset("generalist")
    r = await g.execute("rollback_deploy", {"service": "gateway"}, ctx, board)
    assert r.write_attempt and r.error == "write_not_permitted" and "read-only" in r.content
    r = await g.execute("grep_logs", {}, ctx, board)
    assert r.error == "unknown_tool" and "query_logs" in r.content
    r = await g.execute("query_metrics", {"signal": "cpu"}, ctx, board)
    assert r.error == "invalid_arguments" and "signal" in r.content
    r = await g.execute("query_metrics", {}, ctx, board)
    assert r.error == "invalid_arguments"
    assert board.ids() == []


# --- tools on recorded telemetry ------------------------------------------------------------------
async def test_baseline_metrics_are_quiet(ctx_factory, board):
    ctx, replay = ctx_factory("baseline")
    r = await toolset("generalist").execute("query_metrics", {"signal": "error_rate"}, ctx, board)
    assert r.error is None and r.evidence_id == "ev-0001" and not replay.misses
    data = payload(r)
    assert data["signal"] == "error_rate" and data["total_series"] >= 4
    assert all(s["last"] < 0.05 for s in data["series"])  # current values; the window may hold earlier faults
    assert board.get("ev-0001").raw["status"] == "success"


async def test_s01_shows_payments_errors_npe_and_the_deploy(ctx_factory, board):
    ctx, replay = ctx_factory("s01")
    g = toolset("generalist")
    err = payload(await g.execute("query_metrics", {"signal": "error_rate"}, ctx, board))
    top = {s["series"]: s["max"] for s in err["series"]}
    assert max(v for k, v in top.items() if "payments" in k) > 0.15
    logs = payload(await g.execute("query_logs", {"service": "payments", "level": "error"}, ctx, board))
    assert any("NullPointerException" in (s["signature"] + s.get("exception_message", "")) for s in logs["top_signatures"])
    changes = payload(await g.execute("list_changes", {}, ctx, board))
    deploys = [c for c in changes["changes"] if c["kind"] == "deploy" and c["service"] == "payments"]
    assert any(d["version"] == "1.4" for d in deploys)
    assert not replay.misses


async def test_s03_traces_show_slow_redis(ctx_factory, board):
    ctx, replay = ctx_factory("s03")
    g = toolset("generalist")
    search = payload(await g.execute("query_traces", {"min_duration_ms": 1000}, ctx, board))
    assert search["traces_found"] > 0
    trace_id = search["slowest"][0]["trace_id"]
    trace = await g.execute("query_traces", {"trace_id": trace_id}, ctx, board)
    if trace.error is None:  # only traces captured by the fixture script can be fetched
        spans = payload(trace)
        assert any(s.get("db.system") == "redis" and s["duration_ms"] > 1000 for s in spans["slowest_spans"])


async def test_s09_injection_text_is_flagged_and_sql_error_visible(ctx_factory, board):
    ctx, replay = ctx_factory("s09")
    g = toolset("generalist")
    warn = await g.execute("query_logs", {"level": "warn"}, ctx, board)
    assert 'possible_prompt_injection="true"' in warn.content
    inv = payload(await g.execute("query_logs", {"service": "inventory", "level": "error"}, ctx, board))
    assert any("qty_reserved" in json.dumps(s) for s in inv["top_signatures"])
    assert not replay.misses


async def test_runbook_tool_reads_files_and_lists_index(ctx_factory, board):
    ctx, _ = ctx_factory("baseline")
    g = toolset("generalist")
    idx = payload(await g.execute("read_runbook", {"name": "index"}, ctx, board))
    assert set(idx["available_runbooks"]) >= {"gateway", "orders", "payments", "inventory"}
    rb = payload(await g.execute("read_runbook", {"name": "payments"}, ctx, board))
    assert "inventory" in rb["content"]
    missing = payload(await g.execute("read_runbook", {"name": "redis"}, ctx, board))
    assert "error" in missing


async def test_past_incident_search_is_empty_until_phase_3(ctx_factory, board, store):
    ctx, _ = ctx_factory("baseline")
    out = await READ_TOOLS["search_past_incidents"].run(READ_TOOLS["search_past_incidents"].Input(query="payments deploy"), ctx)
    assert out.summary["count"] == 0
    store.execute("INSERT INTO past_incidents VALUES ('inc-1','payments errors','bad deploy of payments 1.4','rollback','...')")
    out = await READ_TOOLS["search_past_incidents"].run(READ_TOOLS["search_past_incidents"].Input(query="payments deploy"), ctx)
    assert out.summary["count"] == 1
