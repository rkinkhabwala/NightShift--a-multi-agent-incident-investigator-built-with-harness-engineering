"""NightShift API (spec §8). Phase 2: the Alertmanager webhook and read-only incident views."""

from __future__ import annotations

import asyncio
import json
from typing import Any

from fastapi import BackgroundTasks, FastAPI, HTTPException, Request

from harness import config
from harness.store import Store, dumps, now


def create_app(settings: config.Settings | None = None, store: Store | None = None, investigate=None) -> FastAPI:
    """investigate: optional async callable(alert_payload) used when api.auto_investigate is on."""
    settings = settings or config.load()
    store = store or Store(settings.store.path)
    app = FastAPI(title="NightShift", version="0.2.0")
    app.state.store = store
    lock = asyncio.Lock()

    @app.post("/api/alerts")
    async def receive_alerts(request: Request, background: BackgroundTasks) -> dict[str, Any]:
        try:
            payload = await request.json()
        except ValueError:
            raise HTTPException(400, "body must be an Alertmanager webhook JSON payload")
        if not isinstance(payload, dict) or not isinstance(payload.get("alerts"), list):
            raise HTTPException(422, "expected an Alertmanager webhook payload with an 'alerts' list")
        ids, new_firing = [], 0
        for a in payload["alerts"]:
            labels = a.get("labels") or {}
            name, service = str(labels.get("alertname", "unknown")), labels.get("service_name")
            status = str(a.get("status") or payload.get("status") or "firing")
            fp = f"{name}/{service}/{a.get('startsAt')}/{status}"
            dup = store.one("SELECT 1 FROM alerts WHERE fingerprint = ?", (fp,)) is not None
            cur = store.execute(
                "INSERT INTO alerts (received_at, alertname, service, status, starts_at, fingerprint, duplicate, payload_json) "
                "VALUES (?,?,?,?,?,?,?,?)",
                (now(), name, service, status, a.get("startsAt"), fp, int(dup),
                 dumps({**payload, "alerts": [a]})))
            ids.append(cur.lastrowid)
            new_firing += int(status == "firing" and not dup)
        if settings.api.auto_investigate and investigate and new_firing:
            async def run() -> None:
                async with lock:  # one investigation at a time on a local model
                    await investigate(payload)
            background.add_task(run)
        return {"received": len(ids), "ids": ids, "new_firing": new_firing}

    @app.get("/api/alerts")
    def list_alerts(since_id: int = 0, include_duplicates: bool = False, limit: int = 100) -> list[dict[str, Any]]:
        rows = store.all("SELECT * FROM alerts WHERE id > ? AND (? OR duplicate = 0) ORDER BY id LIMIT ?",
                         (since_id, int(include_duplicates), limit))
        return [{**{k: r[k] for k in r.keys() if k != "payload_json"}, "payload": json.loads(r["payload_json"])} for r in rows]

    @app.get("/api/incidents")
    def list_incidents(limit: int = 50) -> list[dict[str, Any]]:
        rows = store.all("SELECT id, status, created_at, updated_at, report_json FROM incidents ORDER BY created_at DESC LIMIT ?", (limit,))
        out = []
        for r in rows:
            report = json.loads(r["report_json"]) if r["report_json"] else None
            out.append({"id": r["id"], "status": r["status"], "created_at": r["created_at"],
                        "summary": report.get("summary") if report else None})
        return out

    @app.get("/api/incidents/{incident_id}")
    def get_incident(incident_id: str) -> dict[str, Any]:
        r = store.one("SELECT * FROM incidents WHERE id = ?", (incident_id,))
        if r is None:
            raise HTTPException(404, f"no incident {incident_id}")
        runs = store.all("SELECT agent, status, steps, model_calls, prompt_tokens, completion_tokens, tool_calls, "
                         "tool_errors, write_attempts, error FROM agent_runs WHERE incident_id = ?", (incident_id,))
        evidence = store.all("SELECT id, tool, args_json, summary_json FROM evidence WHERE incident_id = ? ORDER BY seq", (incident_id,))
        return {"id": r["id"], "status": r["status"], "created_at": r["created_at"],
                "alert": json.loads(r["alert_json"]),
                "report": json.loads(r["report_json"]) if r["report_json"] else None,
                "runs": [dict(x) for x in runs],
                "evidence": [{"id": e["id"].rsplit("/", 1)[1], "tool": e["tool"], "args": json.loads(e["args_json"]),
                              "summary": json.loads(e["summary_json"])} for e in evidence]}

    @app.get("/healthz")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    return app
