"""nightshift CLI: eval run | eval estimate | eval report | eval export-labels | api"""

from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path

from harness import config as config_mod
from harness.store import Store

ROOT = Path(__file__).resolve().parents[1]


def _scenarios(arg: str) -> list[str] | None:
    return None if arg == "all" else [s.strip().upper() for s in arg.split(",") if s.strip()]


def _overrides(pairs: list[str]) -> dict:
    out = {}
    for p in pairs:
        k, _, v = p.partition("=")
        try:
            out[k] = json.loads(v)
        except ValueError:
            out[k] = v
    return out


def cmd_run(a: argparse.Namespace) -> None:
    from evals.runner import EvalOptions, run_eval
    overrides = _overrides(a.set or [])
    if a.model:
        overrides["model.name"] = a.model
    run_id = asyncio.run(run_eval(EvalOptions(scenarios=_scenarios(a.scenarios), runs=a.runs, label=a.label,
                                              resume=a.resume, overrides=overrides,
                                              use_snapshot=False if a.no_snapshot else None)))
    cmd_report(argparse.Namespace(run_id=run_id, write=None))


def cmd_estimate(a: argparse.Namespace) -> None:
    s = config_mod.load()
    n = len(_scenarios(a.scenarios) or range(10)) * a.runs
    role = s.role("generalist")
    calls = role.max_steps + 2  # steps + a possible repair + judge
    store = Store(s.store.path)
    row = store.one("SELECT avg(model_calls) AS c, avg(prompt_tokens) AS p FROM agent_runs")
    measured = store.all("SELECT transcript_json FROM agent_runs ORDER BY rowid DESC LIMIT 20")
    lat = []
    for r in measured:
        for t in json.loads(r["transcript_json"] or "{}").get("transcript", []):
            if t.get("type") == "model" and t.get("latency_ms"):
                lat.append(t["latency_ms"] / 1000)
    per_call = sum(lat) / len(lat) if lat else 25.0
    avg_calls = (row["c"] if row and row["c"] else calls * 0.7)
    agent_min = avg_calls * per_call / 60
    scenario_min = 9.0  # reset + baseline + inject + settle + reset, measured in Phase 1 (S04 longer)
    total_h = n * (agent_min + scenario_min) / 60
    print(f"{n} incidents; up to {calls} model calls each (expected ~{avg_calls:.0f}); "
          f"{'measured' if lat else 'assumed'} {per_call:.0f}s per call -> ~{agent_min:.1f} min of agent time per incident")
    print(f"plus ~{scenario_min:.0f} min of inject/settle/reset per scenario -> about {total_h:.1f} hours in total")


def cmd_report(a: argparse.Namespace) -> None:
    from evals.results import load, markdown
    s = config_mod.load()
    store = Store(s.store.path)
    run_id = a.run_id or store.one("SELECT id FROM eval_runs ORDER BY started_at DESC LIMIT 1")["id"]
    run, results = load(store, run_id)
    md = markdown(run, results)
    print(md)
    if a.write:
        path = Path(a.write)
        text = path.read_text() if path.exists() else "# NightShift results\n\n"
        path.write_text(text.rstrip() + "\n\n" + md)
        print(f"appended to {path}")


def cmd_export_labels(a: argparse.Namespace) -> None:
    s = config_mod.load()
    store = Store(s.store.path)
    rows = store.all("SELECT r.scenario, r.incident_id, r.scores_json FROM eval_results r WHERE r.eval_run_id = ? "
                     "AND r.status = 'SCORED' ORDER BY random() LIMIT ?", (a.run_id, a.n))
    out = ROOT / "evals" / "labels" / "to_label.jsonl"
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w") as f:
        for r in rows:
            sc = json.loads(r["scores_json"])
            f.write(json.dumps({"incident_id": r["incident_id"], "scenario": r["scenario"], "report": sc.get("report"),
                                "auto_root_cause_correct": sc["root_cause_correct"], "human_root_cause_correct": None,
                                "human_citations_supported": None}) + "\n")
    print(f"wrote {len(rows)} reports to {out}; fill in the human_* fields")


def cmd_api(a: argparse.Namespace) -> None:
    import uvicorn
    from api.main import create_app
    s = config_mod.load()
    uvicorn.run(create_app(s), host=s.api.host, port=s.api.port)


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(prog="nightshift")
    sub = p.add_subparsers(dest="cmd", required=True)
    ev = sub.add_parser("eval").add_subparsers(dest="evcmd", required=True)
    r = ev.add_parser("run", help="run scenarios against the live stack and score them")
    r.add_argument("--scenarios", default="all")
    r.add_argument("--runs", type=int, default=1)
    r.add_argument("--label")
    r.add_argument("--resume", help="eval run id to continue")
    r.add_argument("--model", help="override model.name, e.g. llama3.1:8b")
    r.add_argument("--no-snapshot", action="store_true")
    r.add_argument("--set", action="append", help="config override, e.g. roles.generalist.think=true")
    r.set_defaults(func=cmd_run)
    e = ev.add_parser("estimate", help="expected model calls and wall clock")
    e.add_argument("--scenarios", default="all")
    e.add_argument("--runs", type=int, default=3)
    e.set_defaults(func=cmd_estimate)
    rp = ev.add_parser("report", help="print (and optionally append to RESULTS.md) one eval run")
    rp.add_argument("run_id", nargs="?")
    rp.add_argument("--write", help="file to append the tables to, e.g. RESULTS.md")
    rp.set_defaults(func=cmd_report)
    lb = ev.add_parser("export-labels", help="sample reports for hand-labelling (judge agreement)")
    lb.add_argument("run_id")
    lb.add_argument("-n", type=int, default=20)
    lb.set_defaults(func=cmd_export_labels)
    sub.add_parser("api", help="run the NightShift API").set_defaults(func=cmd_api)
    args = p.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
