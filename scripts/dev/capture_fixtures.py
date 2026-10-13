#!/usr/bin/env python3
"""Captures live tool responses for tests/fixtures/<name>.json while a scenario is active.

usage: uv run python scripts/dev/capture_fixtures.py <name> [scenario-id]
With a scenario id: inject, wait settle_seconds, capture, reset. Without: capture the current state.
"""

import asyncio
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

import httpx

from harness import config
from harness.evidence import EvidenceBoard
from harness.store import Store
from harness.tools.base import ToolContext
from harness.tools.http import TelemetryClient
from harness.tools.recording import RecordingTransport
from harness.tools.registry import READ_TOOLS, toolset

ROOT = Path(__file__).resolve().parents[2]
SERVICES = ["gateway", "orders", "payments", "inventory"]

CALLS = (
    [("query_metrics", {"signal": s}) for s in ["error_rate", "request_rate", "latency_p95", "status_codes",
                                                "heap_used_ratio", "restarts", "db_pool_pending", "alerts"]]
    + [("query_metrics", {"signal": "error_rate", "service": s}) for s in SERVICES]
    + [("query_logs", {"level": "error"}), ("query_logs", {"level": "warn"})]
    + [("query_logs", {"service": s, "level": "error"}) for s in SERVICES]
    + [("query_logs", {"service": s}) for s in SERVICES]
    + [("query_traces", {"errors_only": True}), ("query_traces", {"min_duration_ms": 1000})]
    + [("query_traces", {"service": s, "errors_only": True}) for s in SERVICES]
    + [("list_changes", {}), ("list_changes", {"minutes": 30})]
    + [("read_runbook", {"name": "index"})]
)


async def main(name: str, scenario: str | None) -> None:
    s = config.load()
    rec = RecordingTransport(ROOT / "tests/fixtures" / f"{name}.json.gz")
    http = TelemetryClient(s.endpoints, transport=rec)
    ctx = ToolContext(http=http, settings=s, store=Store(":memory:"), runbooks_dir=ROOT / "runbooks")
    board = EvidenceBoard(ctx.store, "capture")
    ctx.store.execute("INSERT INTO incidents VALUES ('capture','{}','OPEN',NULL,'now','now')")
    async with httpx.AsyncClient(base_url=s.endpoints.chaos, timeout=900) as chaos:
        if scenario:
            sc = (await chaos.get(f"/chaos/scenarios/{scenario}")).json()
            print("injecting", scenario, (await chaos.post(f"/chaos/scenarios/{scenario}/inject")).json()["phase"])
            time.sleep(sc["verify"]["settle_seconds"])
        tools = toolset("generalist")
        for tool, args in CALLS:
            r = await tools.execute(tool, args, ctx, board)
            print(f"{tool} {args} -> {r.error or r.evidence_id} ({r.result_chars} chars)")
        # A few trace ids so tempo_get_trace has fixtures too.
        for ev in board.all():
            if ev.tool == "query_traces":
                for t in ev.summary.get("slowest", [])[:2]:
                    r = await tools.execute("query_traces", {"trace_id": t["trace_id"]}, ctx, board)
                    print("trace", t["trace_id"], "->", r.error or r.evidence_id)
        for tool_name in ["list_deploys"]:
            out = await READ_TOOLS[tool_name].run(READ_TOOLS[tool_name].Input(), ctx)
            for d in out.summary["deploys"][:2]:
                await READ_TOOLS["diff_deploy"].run(READ_TOOLS["diff_deploy"].Input(deploy_id=d["deploy_id"]), ctx)
        for svc in SERVICES:
            for t in ("get_config", "get_feature_flags"):
                await READ_TOOLS[t].run(READ_TOOLS[t].Input(service=svc), ctx)
        rec.save()
        print("saved", len(rec.entries), "responses")
        if scenario:
            print("reset", (await chaos.post(f"/chaos/scenarios/{scenario}/reset")).json()["phase"])
    await http.aclose()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None))
