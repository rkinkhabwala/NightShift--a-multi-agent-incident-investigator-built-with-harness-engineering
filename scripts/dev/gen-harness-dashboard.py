#!/usr/bin/env python3
"""Generates telemetry/grafana/dashboards/nightshift-harness.json (spec §5.7 harness observability)."""
import json
import pathlib

PROM = {"type": "prometheus", "uid": "prometheus"}
TEMPO = {"type": "tempo", "uid": "tempo"}
panels, y = [], 0


def ts(title, exprs, unit, x, w=12, h=8, kind="timeseries", desc=""):
    panels.append({"type": kind, "title": title, "description": desc, "datasource": PROM,
                   "gridPos": {"x": x, "y": y, "w": w, "h": h},
                   "fieldConfig": {"defaults": {"unit": unit}, "overrides": []},
                   "options": {"legend": {"displayMode": "list", "placement": "bottom"}},
                   "targets": [{"refId": chr(65 + i), "expr": e, "legendFormat": l} for i, (e, l) in enumerate(exprs)]})


W = "[$__range]"
ts("Agent runs by status", [(f"sum by (status) (increase(nightshift_agent_runs_total{W}))", "{{status}}")], "short", 0, 6, 6, "stat")
ts("Budget-exceeded rate", [(f'sum(increase(nightshift_agent_runs_total{{status="BUDGET_EXCEEDED"}}{W})) / sum(increase(nightshift_agent_runs_total{W}))', "budget exceeded")], "percentunit", 6, 6, 6, "stat")
ts("Tool error rate", [(f"sum(increase(nightshift_tool_errors_total{W})) / sum(increase(nightshift_tool_calls_total{W}))", "tool errors")], "percentunit", 12, 6, 6, "stat",
   desc="Unknown tools, invalid arguments, rejected writes and failed queries, over all tool calls")
ts("Tokens per run", [(f'sum by (kind) (increase(nightshift_tokens_total{W})) / scalar(sum(increase(nightshift_agent_runs_total{W})))', "{{kind}}")], "short", 18, 6, 6, "stat")
y += 6
ts("Wall clock per run (p50 / p95)", [(f"histogram_quantile(0.5, sum by (le) (rate(nightshift_agent_wall_seconds_bucket[30m])))", "p50"),
                                      (f"histogram_quantile(0.95, sum by (le) (rate(nightshift_agent_wall_seconds_bucket[30m])))", "p95")], "s", 0)
ts("Steps per run (p50 / max bucket)", [(f"histogram_quantile(0.5, sum by (le) (rate(nightshift_agent_steps_bucket[30m])))", "p50"),
                                        (f"histogram_quantile(0.95, sum by (le) (rate(nightshift_agent_steps_bucket[30m])))", "p95")], "short", 12)
y += 8
panels.append({"type": "table", "title": "Incident investigations (traces)", "datasource": TEMPO,
               "gridPos": {"x": 0, "y": y, "w": 24, "h": 10},
               "targets": [{"refId": "A", "queryType": "traceql", "limit": 50, "tableType": "traces",
                            "query": '{resource.service.name="nightshift-harness" && name="incident"}'}]})

dash = {"uid": "nightshift-harness", "title": "NightShift - Harness", "tags": ["nightshift"], "schemaVersion": 39,
        "version": 1, "refresh": "30s", "time": {"from": "now-24h", "to": "now"}, "panels": panels}
out = pathlib.Path(__file__).resolve().parents[2] / "telemetry/grafana/dashboards/nightshift-harness.json"
out.write_text(json.dumps(dash, indent=2) + "\n")
print(f"wrote {out} ({len(panels)} panels)")
