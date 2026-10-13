"""Runs one investigation end to end: alert -> snapshot -> agent loop -> stored report."""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from opentelemetry import trace

from agents import generalist
from harness import snapshot as snapshot_mod
from harness import tracing
from harness.config import Settings
from harness.context import ContextBuilder
from harness.evidence import EvidenceBoard
from harness.loop import AgentLoop, RunResult
from harness.models.base import ModelAdapter
from harness.store import Store, dumps, now
from harness.tools.base import ToolContext
from harness.tools.http import TelemetryClient
from harness.tools.registry import toolset

ROOT = Path(__file__).resolve().parents[1]
tracer = trace.get_tracer("nightshift.harness")


@dataclass
class IncidentResult:
    incident_id: str
    run: RunResult
    snapshot: str | None
    alert_text: str


def describe_alert(payload: dict[str, Any]) -> str:
    """Alertmanager webhook payload -> a few readable lines (labels are data, so they are not trusted either)."""
    lines = []
    for a in payload.get("alerts", [])[:10]:
        labels, ann = a.get("labels", {}), a.get("annotations", {})
        lines.append(f"- {labels.get('alertname', '?')} [{a.get('status', payload.get('status', '?'))}] "
                     f"service={labels.get('service_name', '?')} severity={labels.get('severity', '?')} "
                     f"since {a.get('startsAt', '?')}: {ann.get('summary', '')}".strip())
    return "\n".join(lines) or "- (alert payload had no alerts)"


def build_task(alert_text: str, snapshot_text: str | None, window_minutes: int, now_: datetime) -> str:
    parts = [f"## Alert (received {now_.strftime('%Y-%m-%dT%H:%M:%SZ')})", alert_text,
             f"\nInvestigation window: the last {window_minutes} minutes. Tools take a 'minutes' look-back.",
             f"\n## Task\n{generalist.BRIEF}"]
    if snapshot_text:
        parts.append("\n" + snapshot_text)
    return "\n".join(parts)


async def investigate(alert: dict[str, Any], settings: Settings, model: ModelAdapter, store: Store,
                      http: TelemetryClient | None = None, incident_id: str | None = None,
                      use_snapshot: bool | None = None, clock=None) -> IncidentResult:
    incident_id = incident_id or f"inc-{datetime.now(UTC).strftime('%Y%m%d-%H%M%S')}-{uuid.uuid4().hex[:6]}"
    role_cfg = settings.role(generalist.ROLE)
    use_snapshot = settings.snapshot.enabled if use_snapshot is None else use_snapshot
    store.execute("INSERT INTO incidents (id, alert_json, status, created_at, updated_at) VALUES (?,?,?,?,?)",
                  (incident_id, dumps(alert), "INVESTIGATING", now(), now()))
    own_http = http is None
    http = http or TelemetryClient(settings.endpoints)
    ctx = ToolContext(http=http, settings=settings, store=store, runbooks_dir=ROOT / "runbooks",
                      **({"clock": clock} if clock else {}))
    board = EvidenceBoard(store, incident_id)
    tools = toolset(generalist.ROLE)
    alert_text = describe_alert(alert)
    with tracer.start_as_current_span("incident", attributes={"nightshift.incident_id": incident_id,
                                                              "nightshift.model": model.model_id,
                                                              "nightshift.snapshot": use_snapshot}) as span:
        snap_text = None
        if use_snapshot:
            with tracer.start_as_current_span("snapshot"):
                snap = await snapshot_mod.compute(ctx, tools, board, settings.snapshot.window_minutes)
                snap_text = snap.render()
        task = build_task(alert_text, snap_text, settings.tools.default_window_minutes, ctx.clock())
        context = ContextBuilder(generalist.PROMPT, settings.model.num_ctx, settings.context.compaction_ratio,
                                 settings.context.chars_per_token)
        loop = AgentLoop(generalist.ROLE, model, tools, ctx, board, context, role_cfg, generalist.REPORT_MODEL)
        result = await loop.run(task)
        span.set_attribute("nightshift.status", result.status)
    tracing.record_run(generalist.ROLE, result.status, result.stats)
    s = result.stats
    store.execute(
        "INSERT INTO agent_runs (id, incident_id, agent, status, steps, model_calls, prompt_tokens, completion_tokens, "
        "tool_calls, tool_errors, write_attempts, started_at, finished_at, error, transcript_json) "
        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (f"{incident_id}/{generalist.ROLE}", incident_id, generalist.ROLE, result.status, s.steps, s.model_calls,
         s.prompt_tokens, s.completion_tokens, s.tool_calls, s.tool_errors, s.write_attempts, now(), now(),
         result.error, dumps({"task": task, "transcript": result.transcript, "stats": result.to_dict()["stats"]})))
    status = "REPORTED" if result.report is not None else "FAILED"
    store.execute("UPDATE incidents SET status = ?, report_json = ?, updated_at = ? WHERE id = ?",
                  (status, dumps(result.report) if result.report else None, now(), incident_id))
    if own_http:
        await http.aclose()
    return IncidentResult(incident_id, result, snap_text, alert_text)
