import json
from datetime import UTC, datetime

import pytest
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from harness import tracing
from harness.incident import describe_alert, investigate
from harness.models.recorded import ReplayAdapter
from harness.tools.http import TelemetryClient
from harness.tools.recording import ReplayTransport
from tests.conftest import FIXTURES
from tests.scripted import call, finish, payments_deploy, response, write_cassette

EXPORTER = InMemorySpanExporter()

ALERT = {"version": "4", "status": "firing", "receiver": "nightshift", "alerts": [{
    "status": "firing", "startsAt": "2026-10-12T21:52:00Z",
    "labels": {"alertname": "HighErrorRate", "service_name": "gateway", "severity": "page"},
    "annotations": {"summary": "gateway 5xx ratio is 31 % (> 5 %)"}}]}


@pytest.fixture(scope="module", autouse=True)
def in_memory_tracing():
    from harness import config
    tracing.setup(config.load().tracing, "replay", span_exporter=EXPORTER)
    yield


def test_alert_is_described_compactly():
    text = describe_alert(ALERT)
    assert text == ("- HighErrorRate [firing] service=gateway severity=page since 2026-10-12T21:52:00Z: "
                    "gateway 5xx ratio is 31 % (> 5 %)")


async def test_s01_incident_end_to_end_on_recorded_data(tmp_path, settings, store):
    EXPORTER.clear()
    cassette = write_cassette(tmp_path / "s01.jsonl", [
        ({"last_role": "user", "last_contains": ["Incident snapshot", "deploy payments 1.3 -> 1.4", "HighErrorRate"]},
         response(call("query_logs", service="payments", level="error"), call("list_changes"))),
        ({"last_role": "tool"}, finish(**payments_deploy(("ev-0001", "ev-0010")))),
    ])
    http = TelemetryClient(settings.endpoints, transport=ReplayTransport(FIXTURES / "s01.json.gz"))
    result = await investigate(ALERT, settings, ReplayAdapter(cassette), store, http=http, incident_id="inc-s01",
                               clock=lambda: datetime(2026, 10, 12, 22, 0, tzinfo=UTC))
    assert result.run.status == "FINISHED", result.run.error
    assert result.run.report["remediation"]["params"] == {"to_version": "1.3"}
    row = store.one("SELECT status, report_json FROM incidents WHERE id = 'inc-s01'")
    assert row["status"] == "REPORTED" and json.loads(row["report_json"])["root_cause"]["service"] == "payments"
    run = store.one("SELECT * FROM agent_runs WHERE incident_id = 'inc-s01'")
    assert run["model_calls"] == 2 and run["tool_calls"] == 2 and run["write_attempts"] == 0
    assert len(store.all("SELECT id FROM evidence WHERE incident_id = 'inc-s01'")) == 11  # 9 snapshot + 2 agent

    spans = {s.name: s for s in EXPORTER.get_finished_spans()}
    assert {"incident", "snapshot", "agent generalist", "model call", "tool query_logs", "tool list_changes"} <= set(spans)
    incident, agent = spans["incident"], spans["agent generalist"]
    assert agent.parent.span_id == incident.context.span_id
    assert spans["model call"].attributes["gen_ai.usage.input_tokens"] == 900
    assert spans["tool query_logs"].attributes["nightshift.evidence_id"].startswith("ev-")
    assert agent.attributes["nightshift.status"] == "FINISHED"


async def test_snapshot_can_be_disabled(tmp_path, settings, store):
    cassette = write_cassette(tmp_path / "c.jsonl", [
        ({"last_contains": ["HighErrorRate"]}, response(call("query_metrics", signal="error_rate"))),
        finish(**payments_deploy(("ev-0001",))),
    ])
    http = TelemetryClient(settings.endpoints, transport=ReplayTransport(FIXTURES / "s01.json.gz"))
    replay = ReplayAdapter(cassette)
    result = await investigate(ALERT, settings, replay, store, http=http, incident_id="inc-nosnap", use_snapshot=False)
    assert result.snapshot is None and "Incident snapshot" not in replay.requests[0].messages[1]["content"]
    assert result.run.status == "FINISHED"
