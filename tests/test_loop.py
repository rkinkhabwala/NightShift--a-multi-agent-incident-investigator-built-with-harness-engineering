import pytest

from agents.schemas import Report
from harness.context import ContextBuilder
from harness.loop import AgentLoop
from harness.models.recorded import ReplayAdapter
from harness.tools.registry import toolset
from tests.scripted import call, finish, no_cause, payments_deploy, response, write_cassette

GENERALIST = ["list_changes", "query_logs", "query_metrics", "query_traces", "read_runbook", "finish"]
TASK = "ALERT HighErrorRate firing for gateway since 21:50Z. Find the root cause."


def make_loop(tmp_path, ctx_factory, board, settings, entries, fixture="s01", num_ctx=16384, clock=None, **role):
    cfg = settings.role("generalist").model_copy(update=role)
    ctx, _ = ctx_factory(fixture)
    model = ReplayAdapter(write_cassette(tmp_path / "c.jsonl", entries))
    context = ContextBuilder("You are the on-call investigator.", num_ctx, 0.6, 3.5)
    kwargs = {"clock": clock} if clock else {}
    return AgentLoop("generalist", model, toolset("generalist"), ctx, board, context, cfg, Report, **kwargs), model


async def test_happy_path_runs_tools_in_parallel_then_finishes(tmp_path, ctx_factory, board, settings):
    loop, model = make_loop(tmp_path, ctx_factory, board, settings, [
        ({"tools": GENERALIST, "last_role": "user"},
         response(call("query_metrics", signal="error_rate"), call("list_changes"))),
        ({"tools": GENERALIST, "last_role": "tool"}, finish(**payments_deploy(("ev-0001", "ev-0002")))),
    ])
    result = await loop.run(TASK)
    assert result.status == "FINISHED", result.error
    assert result.report["root_cause"]["service"] == "payments"
    assert result.stats.model_calls == 2 and result.stats.tool_calls == 2 and result.stats.tool_errors == 0
    assert board.ids() == ["ev-0001", "ev-0002"] and model.exhausted
    system = model.requests[0].messages[0]["content"]
    assert "untrusted_data" in system and "AGENTS.md" in system


async def test_hallucinated_tool_and_write_attempt_are_counted(tmp_path, ctx_factory, board, settings):
    loop, _ = make_loop(tmp_path, ctx_factory, board, settings, [
        response(call("grep_logs", pattern="error")),
        ({"last_contains": ["unknown tool"]}, response(call("rollback_deploy", service="gateway", to_version="1.9"))),
        ({"last_contains": ["read-only"]}, finish(**no_cause())),
    ], fixture="baseline")
    result = await loop.run(TASK)
    assert result.status == "FINISHED"
    assert result.stats.tool_errors == 2 and result.stats.write_attempts == 1
    assert [t["error"] for t in result.transcript if t["type"] == "tool"] == ["unknown_tool", "write_not_permitted"]


async def test_invalid_finish_is_repaired_once(tmp_path, ctx_factory, board, settings):
    bad = payments_deploy()
    bad["root_cause"]["confidence"] = "very high"
    loop, model = make_loop(tmp_path, ctx_factory, board, settings, [
        response(call("query_metrics", signal="error_rate")),
        finish(**bad),
        ({"tools": [], "format": True, "last_contains": ["confidence"]}, response(content=__import__("json").dumps(payments_deploy()))),
    ])
    result = await loop.run(TASK)
    assert result.status == "FINISHED" and result.stats.repairs == 1
    assert model.requests[-1].tools is None and model.requests[-1].format is not None


async def test_repair_failure_fails_loudly(tmp_path, ctx_factory, board, settings):
    loop, _ = make_loop(tmp_path, ctx_factory, board, settings, [
        finish(status="ROOT_CAUSE_FOUND", summary="it is payments"),
        response(content="sorry, I cannot produce JSON"),
    ])
    result = await loop.run(TASK)
    assert result.status == "FINISH_INVALID" and result.report is None
    assert "root_cause" in result.error


async def test_citing_unknown_evidence_triggers_repair(tmp_path, ctx_factory, board, settings):
    loop, _ = make_loop(tmp_path, ctx_factory, board, settings, [
        response(call("query_metrics", signal="error_rate")),
        finish(**payments_deploy(("ev-0042",))),
        ({"last_contains": ["ev-0042", "ev-0001"]}, response(content=__import__("json").dumps(payments_deploy(("ev-0001",))))),
    ])
    result = await loop.run(TASK)
    assert result.status == "FINISHED" and result.stats.repairs == 1


async def test_step_budget_forces_a_finish_with_only_the_finish_tool(tmp_path, ctx_factory, board, settings):
    loop, model = make_loop(tmp_path, ctx_factory, board, settings, [
        response(call("query_metrics", signal="error_rate")),
        response(call("query_logs", service="payments", level="error")),
        ({"tools": ["finish"], "last_contains": ["step budget"]}, finish(**payments_deploy(("ev-0001", "ev-0002")))),
    ], max_steps=2)
    result = await loop.run(TASK)
    assert result.status == "BUDGET_EXCEEDED" and result.budget_reason == "step"
    assert result.report["root_cause"]["service"] == "payments"


async def test_forced_finish_failure_still_returns_a_partial_report(tmp_path, ctx_factory, board, settings):
    loop, _ = make_loop(tmp_path, ctx_factory, board, settings, [
        response(call("query_metrics", signal="error_rate")),
        response(content="I need more time"),
    ], max_steps=1)
    result = await loop.run(TASK)
    assert result.status == "BUDGET_EXCEEDED" and result.report["status"] == "NO_ROOT_CAUSE"
    assert "ev-0001" in result.report["summary"]


async def test_text_reply_is_nudged_and_json_text_is_accepted(tmp_path, ctx_factory, board, settings):
    loop, _ = make_loop(tmp_path, ctx_factory, board, settings, [
        response(content="Let me think about the gateway."),
        ({"last_contains": ["without calling a tool"]}, response(content="Report: " + __import__("json").dumps(no_cause()))),
    ], fixture="baseline")
    result = await loop.run(TASK)
    assert result.status == "FINISHED" and result.stats.nudges == 1
    assert result.report["status"] == "NO_ROOT_CAUSE"


async def test_tool_calls_per_step_are_capped(tmp_path, ctx_factory, board, settings):
    many = [call("query_metrics", signal=s) for s in ["error_rate", "request_rate", "latency_p95", "restarts", "heap_used_ratio"]]
    loop, _ = make_loop(tmp_path, ctx_factory, board, settings, [response(*many), finish(**no_cause())],
                        fixture="baseline", max_tool_calls_per_step=3)
    result = await loop.run(TASK)
    assert result.stats.tool_calls == 3 and result.stats.tool_errors == 2 and len(board.ids()) == 3


async def test_time_budget(tmp_path, ctx_factory, board, settings):
    ticks = iter([0, 0, 0, 1000, 1000, 1000, 1000, 1000])
    loop, _ = make_loop(tmp_path, ctx_factory, board, settings, [
        response(call("query_metrics", signal="error_rate")),
        ({"tools": ["finish"]}, finish(**no_cause())),
    ], fixture="baseline", clock=lambda: next(ticks), wall_clock_s=60)
    result = await loop.run(TASK)
    assert result.status == "BUDGET_EXCEEDED" and result.budget_reason == "time"


async def test_input_token_budget(tmp_path, ctx_factory, board, settings):
    loop, _ = make_loop(tmp_path, ctx_factory, board, settings, [
        response(call("query_metrics", signal="error_rate"), prompt_tokens=5000),
        ({"tools": ["finish"]}, finish(**no_cause())),
    ], fixture="baseline", max_input_tokens=4000)
    result = await loop.run(TASK)
    assert result.status == "BUDGET_EXCEEDED" and result.budget_reason == "input-token"


async def test_compaction_keeps_evidence_ids_and_latest_results(tmp_path, ctx_factory, board, settings):
    loop, model = make_loop(tmp_path, ctx_factory, board, settings, [
        response(call("query_logs", service="payments")),
        response(call("query_logs", service="orders")),
        response(call("query_logs", service="gateway")),
        finish(**no_cause()),
    ], num_ctx=6000)
    result = await loop.run(TASK)
    assert result.status == "FINISHED" and result.stats.compactions >= 1
    last = model.requests[-1].messages
    tool_msgs = [m["content"] for m in last if m["role"] == "tool"]
    assert any(c.startswith("[compacted; full result is evidence ev-0001]") for c in tool_msgs)
    assert tool_msgs[-1].startswith("<untrusted_data")  # newest result stays in full
