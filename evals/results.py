"""Aggregates eval results and writes RESULTS.md tables (spec §7)."""

from __future__ import annotations

import json
import statistics
from typing import Any

from harness.store import Store


def load(store: Store, run_id: str) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    run = store.one("SELECT * FROM eval_runs WHERE id = ?", (run_id,))
    if run is None:
        raise SystemExit(f"no eval run {run_id}")
    rows = store.all("SELECT * FROM eval_results WHERE eval_run_id = ? ORDER BY scenario, run_index", (run_id,))
    results = []
    for r in rows:
        d = {"scenario": r["scenario"], "run_index": r["run_index"], "status": r["status"], "error": r["error"],
             "incident_id": r["incident_id"]}
        if r["scores_json"]:
            d.update(json.loads(r["scores_json"]))
        results.append(d)
    return dict(run), results


def _mean(xs: list[float]) -> float | None:
    xs = [x for x in xs if x is not None]
    return round(statistics.fmean(xs), 3) if xs else None


def aggregate(results: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    by: dict[str, list[dict[str, Any]]] = {}
    for r in results:
        by.setdefault(r["scenario"], []).append(r)
    by["ALL"] = list(results)
    out = {}
    for sid, rs in by.items():
        scored = [r for r in rs if r["status"] == "SCORED"]
        out[sid] = {
            "runs": len(rs), "scored": len(scored), "errors": len(rs) - len(scored),
            "root_cause_accuracy": _mean([float(r["root_cause_correct"]) for r in scored]),
            "remediation_acceptable": _mean([float(r["remediation_acceptable"]) for r in scored]),
            "citation_precision": _mean([r["citation_precision"] for r in scored]),
            "unapproved_writes": sum(r["unapproved_write_attempts"] for r in scored),
            "injection_compliance": sum(r["injection_compliance"] for r in scored),
            "forbidden_proposed": sum(int(r["forbidden_action_proposed"]) for r in scored),
            "budget_exceeded": sum(int(r["budget_exceeded"]) for r in scored),
            "judge_used": sum(int(r.get("root_cause_method") == "judge") for r in scored),
            "synthetic_alerts": sum(int(r.get("synthetic_alert", False)) for r in scored),
            "wall_clock_s": _mean([r["wall_clock_s"] for r in scored]),
            "tokens": _mean([r["prompt_tokens"] + r["completion_tokens"] for r in scored]),
            "model_calls": _mean([r["model_calls"] for r in scored]),
            "tool_error_rate": _mean([r["tool_error_rate"] for r in scored]),
        }
    return out


def _fmt(v: Any, pct: bool = False) -> str:
    if v is None:
        return "–"
    if pct:
        return f"{v:.0%}"
    return f"{v:g}" if isinstance(v, float) else str(v)


def markdown(run: dict[str, Any], results: list[dict[str, Any]]) -> str:
    agg = aggregate(results)
    cfg = json.loads(run["config_json"])
    lines = [
        f"### {run['label'] or run['id']}",
        "",
        f"- eval run `{run['id']}`, git `{run['git_sha']}`, model `{run['model']}` (Ollama {run['ollama_version']}), "
        f"config `{run['config_hash']}`, {run['runs']} run(s) per scenario, started {run['started_at'][:16]}Z",
        f"- num_ctx {cfg['model']['num_ctx']}, temperature {cfg['roles']['generalist']['temperature']}, "
        f"think {cfg['roles']['generalist']['think']}, max_steps {cfg['roles']['generalist']['max_steps']}, "
        f"snapshot {cfg['snapshot']['enabled']}, seed {cfg['model']['seed']}",
        "",
        "| scenario | root cause | remediation | citation precision | safety (writes / injection) | budget exceeded | wall clock (s) | tokens | model calls | tool error rate |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for sid in sorted(k for k in agg if k != "ALL") + ["ALL"]:
        a = agg[sid]
        name = f"**{sid}**" if sid == "ALL" else sid
        err = f" ({a['errors']} errors)" if a["errors"] else ""
        lines.append(f"| {name}{err} | {_fmt(a['root_cause_accuracy'], True)} | {_fmt(a['remediation_acceptable'], True)} | "
                     f"{_fmt(a['citation_precision'])} | {a['unapproved_writes']} / {a['injection_compliance']} | "
                     f"{a['budget_exceeded']} | {_fmt(a['wall_clock_s'])} | {_fmt(a['tokens'])} | {_fmt(a['model_calls'])} | "
                     f"{_fmt(a['tool_error_rate'])} |")
    a = agg["ALL"]
    lines += ["", f"Judge used for {a['judge_used']} of {a['scored']} root-cause decisions; "
                  f"{a['synthetic_alerts']} run(s) used a synthetic alert; forbidden actions proposed: {a['forbidden_proposed']}."]
    return "\n".join(lines) + "\n"
