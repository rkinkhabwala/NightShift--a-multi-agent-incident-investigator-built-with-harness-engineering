from harness import snapshot
from harness.tools.registry import toolset


async def test_s01_snapshot_shows_payments_errors_and_the_deploy(ctx_factory, board):
    ctx, replay = ctx_factory("s01")
    snap = await snapshot.compute(ctx, toolset("generalist"), board, 30)
    text = snap.render()
    assert not snap.failed and not replay.misses, (snap.failed, replay.misses)
    assert any(o.startswith("5xx ratio payments") for o in snap.observations)
    assert any("deploy payments 1.3 -> 1.4" in o for o in snap.observations)
    assert any("NullPointerException" in o for o in snap.observations)
    assert set(snap.evidence) == {q[0] for q in snapshot.QUERIES}
    for eid in snap.evidence.values():
        assert f"[{eid}]" in text or eid in board.ids()


async def test_s03_snapshot_shows_latency_and_slow_traces(ctx_factory, board):
    ctx, _ = ctx_factory("s03")
    snap = await snapshot.compute(ctx, toolset("generalist"), board, 30)
    assert any(o.startswith("p95 latency inventory") for o in snap.observations)
    assert any(o.startswith("traces slower than 1 s") for o in snap.observations)


async def test_s09_snapshot_carries_the_bad_deploy(ctx_factory, board):
    ctx, _ = ctx_factory("s09")
    snap = await snapshot.compute(ctx, toolset("generalist"), board, 30)
    assert any("deploy inventory 2.2 -> 2.4" in o for o in snap.observations)
    assert any(o.startswith("5xx ratio inventory") for o in snap.observations)


async def test_quiet_system_has_no_anomalies(ctx_factory, board):
    ctx, _ = ctx_factory("quiet")
    snap = await snapshot.compute(ctx, toolset("generalist"), board, 30)
    anomalies = [o for o in snap.observations if o.startswith(("5xx ratio", "p95 latency", "process restarts", "heap"))]
    assert anomalies == [], anomalies
    assert "Incident snapshot" in snap.render()
