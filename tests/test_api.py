from fastapi.testclient import TestClient

from api.main import create_app
from tests.test_incident import ALERT


def client(settings, store, **kw):
    return TestClient(create_app(settings, store, **kw))


def test_alertmanager_payload_is_stored_and_duplicates_marked(settings, store):
    c = client(settings, store)
    r = c.post("/api/alerts", json=ALERT)
    assert r.status_code == 200 and r.json()["new_firing"] == 1
    again = c.post("/api/alerts", json=ALERT).json()
    assert again["new_firing"] == 0
    listed = c.get("/api/alerts").json()
    assert len(listed) == 1 and listed[0]["alertname"] == "HighErrorRate" and listed[0]["service"] == "gateway"
    assert listed[0]["payload"]["alerts"][0]["labels"]["severity"] == "page"
    assert len(c.get("/api/alerts", params={"include_duplicates": True}).json()) == 2
    assert c.get("/api/alerts", params={"since_id": listed[0]["id"]}).json() == []


def test_bad_payloads_are_rejected(settings, store):
    c = client(settings, store)
    assert c.post("/api/alerts", content=b"not json", headers={"content-type": "application/json"}).status_code == 400
    assert c.post("/api/alerts", json={"hello": "world"}).status_code == 422


def test_incident_views(settings, store):
    c = client(settings, store)
    assert c.get("/api/incidents/nope").status_code == 404
    store.execute("UPDATE incidents SET status='REPORTED', report_json=? WHERE id='inc-test'",
                  ('{"status": "NO_ROOT_CAUSE", "summary": "all quiet"}',))
    assert c.get("/api/incidents").json()[0]["summary"] == "all quiet"
    body = c.get("/api/incidents/inc-test").json()
    assert body["report"]["status"] == "NO_ROOT_CAUSE" and body["runs"] == [] and body["evidence"] == []


def test_auto_investigate_only_when_enabled(settings, store):
    calls = []

    async def fake(payload):
        calls.append(payload)

    client(settings, store, investigate=fake).post("/api/alerts", json=ALERT)
    assert calls == []
    on = settings.model_copy(update={"api": settings.api.model_copy(update={"auto_investigate": True})})
    alert2 = {**ALERT, "alerts": [{**ALERT["alerts"][0], "startsAt": "2026-10-12T23:00:00Z"}]}
    client(on, store, investigate=fake).post("/api/alerts", json=alert2)
    assert len(calls) == 1
