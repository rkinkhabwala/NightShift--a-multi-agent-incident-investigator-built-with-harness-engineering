"""Eval runner (spec §7): reset -> inject -> wait for the alert -> investigate -> score -> reset."""

from __future__ import annotations

import asyncio
import json
import socket
import subprocess
import threading
import time
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import httpx

from evals import judge as judge_mod
from evals import scoring
from harness import config as config_mod
from harness import incident as incident_mod
from harness import tracing
from harness.config import Settings
from harness.models.ollama import OllamaAdapter
from harness.store import Store, dumps, now

ROOT = Path(__file__).resolve().parents[1]


def log(msg: str) -> None:
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


def git_sha() -> str:
    try:
        sha = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
        dirty = subprocess.run(["git", "status", "--porcelain", "--untracked-files=no"], cwd=ROOT,
                               capture_output=True, text=True).stdout.strip()
        return sha + ("-dirty" if dirty else "")
    except OSError:
        return "unknown"


# --- target-system control (privileged: only the runner talks to /chaos/*) ------------------------------
class Chaos:
    def __init__(self, s: Settings):
        self.http = httpx.AsyncClient(base_url=s.endpoints.chaos, timeout=900)
        self.prom = httpx.AsyncClient(base_url=s.endpoints.prometheus, timeout=30)

    async def scenarios(self) -> list[str]:
        return [x["id"] for x in (await self.http.get("/chaos/scenarios")).json()]

    async def scenario(self, sid: str) -> dict[str, Any]:
        r = await self.http.get(f"/chaos/scenarios/{sid}")
        r.raise_for_status()
        return r.json()

    async def inject(self, sid: str) -> dict[str, Any]:
        return (await self.http.post(f"/chaos/scenarios/{sid}/inject")).json()

    async def reset(self, sid: str | None = None) -> dict[str, Any]:
        path = f"/chaos/scenarios/{sid}/reset" if sid else "/chaos/reset-all"
        return (await self.http.post(path)).json()

    async def _prom(self, q: str) -> float | None:
        r = (await self.prom.get("/api/v1/query", params={"query": q})).json()
        res = r.get("data", {}).get("result", [])
        return float(res[0]["value"][1]) if res else None

    async def baseline_problems(self) -> list[str]:
        p = []
        st = (await self.http.get("/chaos/status")).json()
        if st.get("phase") != "IDLE":
            p.append(f"chaos phase {st.get('phase')}")
        for name, svc in (await self.http.get("/ops/services")).json().items():
            if svc.get("version") != svc.get("baseline") or svc.get("deployStatus") != "COMPLETED":
                p.append(f"{name} at {svc.get('version')} ({svc.get('deployStatus')})")
        alerts = (await self.prom.get("/api/v1/alerts")).json().get("data", {}).get("alerts", [])
        firing = [f"{a['labels'].get('alertname')}/{a['labels'].get('service_name')}" for a in alerts if a.get("state") == "firing"]
        if firing:
            p.append(f"alerts firing {firing}")
        r5 = await self._prom('(sum(rate(http_server_request_duration_seconds_count{service_name="gateway",http_response_status_code=~"5.."}[1m])) or vector(0)) / sum(rate(http_server_request_duration_seconds_count{service_name="gateway"}[1m]))')
        if r5 is None or r5 >= 0.02:
            p.append(f"gateway 5xx ratio {r5}")
        rate = await self._prom('sum(rate(http_server_request_duration_seconds_count{service_name="gateway",http_route=~"/api/orders.*"}[1m]))')
        if not rate or rate < 2:
            p.append(f"no load ({rate} req/s; is k6 paused?)")
        return p

    async def wait_baseline(self, timeout_s: float) -> None:
        deadline = time.monotonic() + timeout_s
        problems: list[str] = []
        while time.monotonic() < deadline:
            problems = await self.baseline_problems()
            if not problems:
                return
            await asyncio.sleep(10)
        raise RuntimeError(f"not at baseline after {timeout_s:.0f}s: {problems}")

    async def aclose(self) -> None:
        await self.http.aclose()
        await self.prom.aclose()


# --- the alert receiver --------------------------------------------------------------------------------
def ensure_api(s: Settings) -> threading.Thread | None:
    """Starts the NightShift API in-process unless something already listens on the port."""
    with socket.socket() as sock:
        if sock.connect_ex(("127.0.0.1", s.api.port)) == 0:
            log(f"using the NightShift API already running on :{s.api.port}")
            return None
    import uvicorn
    from api.main import create_app
    server = uvicorn.Server(uvicorn.Config(create_app(s), host=s.api.host, port=s.api.port, log_level="warning"))
    t = threading.Thread(target=server.run, daemon=True, name="nightshift-api")
    t.start()
    for _ in range(50):
        with socket.socket() as sock:
            if sock.connect_ex(("127.0.0.1", s.api.port)) == 0:
                log(f"NightShift API listening on :{s.api.port}")
                return t
        time.sleep(0.1)
    raise RuntimeError("NightShift API did not start")


async def wait_for_alert(store: Store, after_id: int, expected: list[str], timeout_s: float) -> tuple[dict[str, Any] | None, int | None]:
    """First new firing alert (an expected alertname if the scenario lists any)."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        rows = store.all("SELECT id, alertname, payload_json FROM alerts WHERE id > ? AND status = 'firing' AND duplicate = 0 ORDER BY id",
                         (after_id,))
        for r in rows:
            if not expected or r["alertname"] in expected:
                return json.loads(r["payload_json"]), r["id"]
        await asyncio.sleep(5)
    return None, None


def synthetic_alert(scenario: dict[str, Any]) -> dict[str, Any]:
    name = (scenario.get("expectedAlerts") or ["HighErrorRate"])[0]
    return {"version": "4", "status": "firing", "receiver": "nightshift", "synthetic": True, "alerts": [{
        "status": "firing", "startsAt": datetime.now(UTC).isoformat(),
        "labels": {"alertname": name, "service_name": "gateway", "severity": "page"},
        "annotations": {"summary": f"{name} (synthetic: no real alert arrived in time)"}}]}


# --- the run -------------------------------------------------------------------------------------------
@dataclass
class EvalOptions:
    scenarios: list[str] | None
    runs: int
    label: str | None = None
    resume: str | None = None
    overrides: dict[str, Any] | None = None
    use_snapshot: bool | None = None


async def preflight(s: Settings, model: OllamaAdapter) -> str:
    try:
        version = await model.version()
    except Exception as e:  # noqa: BLE001
        raise SystemExit(f"Ollama is not reachable at {s.model.base_url} ({e}). Start the Ollama app first.")
    async with httpx.AsyncClient(base_url=s.model.base_url, timeout=30) as c:
        tags = [m["name"] for m in (await c.get("/api/tags")).json().get("models", [])]
    if s.model.name not in tags and f"{s.model.name}:latest" not in tags:
        raise SystemExit(f"model {s.model.name} is not pulled (have: {tags}). Run: ollama pull {s.model.name}")
    return version


async def run_eval(opts: EvalOptions) -> str:
    s = config_mod.load(overrides=opts.overrides)
    store = Store(s.store.path)
    model = OllamaAdapter(s.model)
    ollama_version = await preflight(s, model)
    tracing.setup(s.tracing, s.model.name)
    ensure_api(s)
    chaos = Chaos(s)
    ids = opts.scenarios or await chaos.scenarios()

    if opts.resume:
        row = store.one("SELECT * FROM eval_runs WHERE id = ?", (opts.resume,))
        if row is None:
            raise SystemExit(f"no eval run {opts.resume}")
        run_id, ids, runs = row["id"], json.loads(row["scenarios"]), row["runs"]
        if row["config_hash"] != s.fingerprint():
            log(f"WARNING: config changed since this run started ({row['config_hash']} -> {s.fingerprint()})")
    else:
        run_id = f"eval-{datetime.now().strftime('%Y%m%d-%H%M%S')}-{uuid.uuid4().hex[:4]}"
        runs = opts.runs
        store.execute("INSERT INTO eval_runs (id, started_at, git_sha, model, ollama_version, config_hash, config_json, "
                      "scenarios, runs, label) VALUES (?,?,?,?,?,?,?,?,?,?)",
                      (run_id, now(), git_sha(), model.model_id, ollama_version, s.fingerprint(),
                       dumps(s.model_dump(mode="json")), json.dumps(ids), runs, opts.label))
    log(f"eval {run_id}: {len(ids)} scenarios x {runs} runs, model {model.model_id} (Ollama {ollama_version}), "
        f"config {s.fingerprint()}, git {git_sha()}")
    done = {(r["scenario"], r["run_index"]) for r in
            store.all("SELECT scenario, run_index FROM eval_results WHERE eval_run_id = ? AND status = 'SCORED'", (run_id,))}
    try:
        for run_index in range(1, runs + 1):
            for sid in ids:
                if (sid, run_index) in done:
                    continue
                await one(run_id, sid, run_index, s, store, model, chaos, opts)
    finally:
        log("restoring baseline")
        try:
            await chaos.reset()
        except Exception as e:  # noqa: BLE001
            log(f"reset-all failed: {e}")
        store.execute("UPDATE eval_runs SET finished_at = ? WHERE id = ?", (now(), run_id))
        await chaos.aclose()
        tracing.shutdown()
    return run_id


async def one(run_id: str, sid: str, run_index: int, s: Settings, store: Store, model: OllamaAdapter,
              chaos: Chaos, opts: EvalOptions) -> None:
    scenario = await chaos.scenario(sid)
    log(f"--- {sid} run {run_index}: {scenario['title']}")
    incident_id = f"{run_id}-{sid}-r{run_index}"
    try:
        await chaos.reset()
        await chaos.wait_baseline(s.eval.baseline_timeout_s)
        last = store.one("SELECT coalesce(max(id), 0) AS m FROM alerts")["m"]
        st = await chaos.inject(sid)
        if st.get("phase") != "ACTIVE":
            raise RuntimeError(f"inject failed: {[x for x in st.get('steps', []) if not x.get('ok')]}")
        expected = scenario.get("expectedAlerts") or []
        if sid == "S10" or not expected:
            expected = [step["fireAlert"]["alertname"] for step in scenario["inject"] if step.get("fireAlert")] or expected
        wait = scenario["verify"]["settle_seconds"] + s.eval.alert_wait_extra_s
        log(f"    injected; waiting up to {wait:.0f}s for {expected or 'any'} alert")
        t_alert = time.monotonic()
        alert, alert_id = await wait_for_alert(store, last, expected, wait)
        if alert is None:
            log("    no real alert arrived; using a synthetic one (flagged)")
            alert = synthetic_alert(scenario)
        log(f"    alert after {time.monotonic() - t_alert:.0f}s: {incident_mod.describe_alert(alert)[:140]}")
        result = await incident_mod.investigate(alert, s, model, store, incident_id=incident_id,
                                                use_snapshot=opts.use_snapshot)
        if alert_id:
            store.execute("UPDATE alerts SET incident_id = ? WHERE id = ?", (incident_id, alert_id))
        evidence = {r["id"].rsplit("/", 1)[-1]: {"tool": r["tool"], "args": json.loads(r["args_json"]),
                                                "summary": json.loads(r["summary_json"])}
                    for r in store.all("SELECT id, tool, args_json, summary_json FROM evidence WHERE incident_id = ?", (incident_id,))}
        run = result.run.to_dict()
        rc_ok, _ = scoring.root_cause(scenario, run["report"])
        verdict = None
        if rc_ok is None and run["report"]:
            v = await judge_mod.judge(model, scenario, run["report"])
            verdict = v.correct
            log(f"    judge ({judge_mod.VERSION}): {v.correct} - {v.reason}")
        sc = scoring.score(scenario, run, evidence, judge_verdict=verdict)
        payload = {**sc.to_dict(), "synthetic_alert": bool(alert.get("synthetic")), "judge_version": judge_mod.VERSION,
                   "report": run["report"]}
        store.execute("INSERT OR REPLACE INTO eval_results (eval_run_id, scenario, run_index, incident_id, status, "
                      "scores_json, error, created_at) VALUES (?,?,?,?,?,?,?,?)",
                      (run_id, sid, run_index, incident_id, "SCORED", dumps(payload), None, now()))
        log(f"    {result.run.status}: root cause {'OK' if sc.root_cause_correct else 'WRONG'} ({sc.root_cause_detail}); "
            f"remediation {'OK' if sc.remediation_acceptable else 'no'}; citations {sc.citation_precision}; "
            f"safety {'OK' if sc.safety_ok else 'VIOLATION'}; {sc.wall_clock_s}s, {sc.prompt_tokens}+{sc.completion_tokens} tokens")
    except Exception as e:  # noqa: BLE001 - one failed pair must not stop an overnight run
        log(f"    ERROR: {e!r}")
        store.execute("INSERT OR REPLACE INTO eval_results (eval_run_id, scenario, run_index, incident_id, status, "
                      "scores_json, error, created_at) VALUES (?,?,?,?,?,?,?,?)",
                      (run_id, sid, run_index, incident_id, "ERROR", None, repr(e)[:2000], now()))
    finally:
        try:
            await chaos.reset(sid)
        except Exception as e:  # noqa: BLE001
            log(f"    reset failed: {e!r}")
