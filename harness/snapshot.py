"""Deterministic pre-analysis (spec §5.9): before the model sees anything, code computes a compact
"incident snapshot": change points on key metrics, top error signatures, deploy/config/flag changes
in the window and the slowest/error traces. Each part is stored on the evidence board, so the agent
can cite it. The snapshot states observations, never conclusions."""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass, field
from typing import Any

from harness.evidence import EvidenceBoard
from harness.tools.base import ToolContext, ToolSet

ERROR_RATIO = 0.02      # 5xx ratio worth mentioning
LATENCY_S = 0.3         # p95 worth mentioning (baseline is ~0.05 s)
HEAP_RATIO = 0.85

QUERIES: list[tuple[str, str, dict[str, Any]]] = [
    ("error_rate", "query_metrics", {"signal": "error_rate"}),
    ("latency", "query_metrics", {"signal": "latency_p95"}),
    ("restarts", "query_metrics", {"signal": "restarts"}),
    ("heap", "query_metrics", {"signal": "heap_used_ratio"}),
    ("changes", "list_changes", {}),
    ("error_logs", "query_logs", {"level": "error"}),
    ("warn_logs", "query_logs", {"level": "warn"}),
    ("error_traces", "query_traces", {"errors_only": True}),
    ("slow_traces", "query_traces", {"min_duration_ms": 1000}),
]


@dataclass
class Snapshot:
    observations: list[str] = field(default_factory=list)
    evidence: dict[str, str] = field(default_factory=dict)     # part -> evidence id
    failed: dict[str, str] = field(default_factory=dict)       # part -> error

    def render(self) -> str:
        head = ("## Incident snapshot (computed by the harness from telemetry; observations, not conclusions; "
                "cite the evidence ids)")
        body = "\n".join(f"- {o}" for o in self.observations) or "- No anomalies found in the window."
        failed = "".join(f"\n- (could not compute {k}: {v})" for k, v in self.failed.items())
        return f"{head}\n{body}{failed}"


def _payload(content: str) -> dict[str, Any]:
    return json.loads(content.split("\n", 1)[1].rsplit("\n", 1)[0])


def _svc(series: str) -> str:
    return series.split('service_name="', 1)[1].split('"', 1)[0] if 'service_name="' in series else series


async def compute(ctx: ToolContext, tools: ToolSet, board: EvidenceBoard, window_minutes: int) -> Snapshot:
    snap = Snapshot()
    calls = [(part, tool, {**args, "minutes": window_minutes} if tool != "list_changes" else args)
             for part, tool, args in QUERIES]
    results = await asyncio.gather(*(tools.execute(tool, args, ctx, board) for _, tool, args in calls))
    data: dict[str, dict[str, Any]] = {}
    for (part, _, _), r in zip(calls, results):
        if r.error:
            snap.failed[part] = r.error
        else:
            snap.evidence[part] = r.evidence_id
            data[part] = _payload(r.content)
    ev = snap.evidence
    obs = snap.observations

    for s in data.get("error_rate", {}).get("series", []):
        if s["max"] >= ERROR_RATIO:
            cp = s.get("change_point")
            when = f", step change at {cp['at']} ({cp['mean_before']:.1%} -> {cp['mean_after']:.1%})" if cp else ""
            obs.append(f"5xx ratio {_svc(s['series'])}: max {s['max']:.1%}, now {s['last']:.1%}{when} [{ev['error_rate']}]")
    for s in data.get("latency", {}).get("series", []):
        if s["max"] >= LATENCY_S:
            cp = s.get("change_point")
            when = f", step change at {cp['at']} ({cp['mean_before']:.2f}s -> {cp['mean_after']:.2f}s)" if cp else ""
            obs.append(f"p95 latency {_svc(s['series'])}: max {s['max']:.2f}s, now {s['last']:.2f}s{when} [{ev['latency']}]")
    for s in data.get("restarts", {}).get("series", []):
        if s["max"] >= 1:
            obs.append(f"process restarts {_svc(s['series'])}: up to {int(s['max'])} in 10 min [{ev['restarts']}]")
    for s in data.get("heap", {}).get("series", []):
        if s["max"] >= HEAP_RATIO:
            obs.append(f"heap {_svc(s['series'])}: max {s['max']:.0%} of limit, now {s['last']:.0%} [{ev['heap']}]")
    changes = [c for c in data.get("changes", {}).get("changes", []) if c.get("initiated_by") != "bootstrap"]
    for c in changes[:8]:
        if c["kind"] == "deploy":
            obs.append(f"deploy {c['service']} {c.get('previous_version')} -> {c['version']} at {c['at']} "
                       f"by {c.get('initiated_by')} [{ev['changes']}]")
        else:
            obs.append(f"{c['kind']} change {c['service']} {c['key']}: {c.get('old')} -> {c.get('new')} at {c['at']} "
                       f"by {c.get('changed_by')} [{ev['changes']}]")
    if len(changes) > 8:
        obs.append(f"... {len(changes) - 8} more changes in the window [{ev['changes']}]")
    logs = data.get("error_logs", {})
    for sig in logs.get("top_signatures", [])[:4]:
        obs.append(f"error log x{sig['count']}: {sig['signature'][:140]} [{ev['error_logs']}]")
    warns = data.get("warn_logs", {})
    for sig in warns.get("top_signatures", [])[:2]:
        if sig["count"] >= 20:
            obs.append(f"warning log x{sig['count']}: {sig['signature'][:140]} [{ev['warn_logs']}]")
    for part in ("error_traces", "slow_traces"):
        t = data.get(part, {})
        if t.get("traces_found"):
            label = "traces with errors" if part == "error_traces" else "traces slower than 1 s"
            roots = ", ".join(f"{k} x{v}" for k, v in t.get("by_root_service", {}).items())
            obs.append(f"{label}: {t['traces_found']} found (root services: {roots}) [{ev[part]}]")
    return snap
