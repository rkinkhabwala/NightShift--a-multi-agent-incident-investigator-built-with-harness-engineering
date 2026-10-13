from datetime import UTC, datetime
from pathlib import Path

import pytest

from harness import config
from harness.evidence import EvidenceBoard
from harness.store import Store
from harness.tools.base import ToolContext
from harness.tools.http import TelemetryClient
from harness.tools.recording import ReplayTransport

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "tests" / "fixtures"


@pytest.fixture
def settings():
    return config.load()


@pytest.fixture
def store():
    s = Store(":memory:")
    s.execute("INSERT INTO incidents (id, alert_json, status, created_at, updated_at) VALUES ('inc-test', '{}', 'OPEN', 'now', 'now')")
    return s


@pytest.fixture
def board(store):
    return EvidenceBoard(store, "inc-test")


def make_ctx(settings, store, fixture: str) -> tuple[ToolContext, ReplayTransport]:
    replay = ReplayTransport(FIXTURES / f"{fixture}.json.gz")
    ctx = ToolContext(http=TelemetryClient(settings.endpoints, transport=replay), settings=settings, store=store,
                      runbooks_dir=ROOT / "runbooks", clock=lambda: datetime(2026, 10, 12, 22, 0, tzinfo=UTC))
    return ctx, replay


@pytest.fixture
def ctx_factory(settings, store):
    return lambda fixture: make_ctx(settings, store, fixture)
