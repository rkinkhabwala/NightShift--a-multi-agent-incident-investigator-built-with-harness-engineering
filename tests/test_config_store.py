import pytest
from pydantic import ValidationError

from harness import config
from harness.store import SCHEMA_VERSION, Store


def test_default_config_loads_and_has_generalist_role():
    s = config.load()
    assert s.model.name == "qwen3:8b"
    assert s.model.num_ctx == 16384
    r = s.role("generalist")
    assert r.max_steps == 12 and r.think is False
    assert len(s.fingerprint()) == 12


def test_overrides_apply_and_change_the_fingerprint():
    base = config.load()
    other = config.load(overrides={"model.name": "llama3.1:8b", "roles.generalist.temperature": 0.1})
    assert other.model.name == "llama3.1:8b"
    assert other.role("generalist").temperature == 0.1
    assert other.fingerprint() != base.fingerprint()


def test_invalid_values_and_unknown_keys_are_rejected():
    with pytest.raises(ValidationError):
        config.load(overrides={"model.num_ctx": 100})
    with pytest.raises(ValidationError):
        config.load(overrides={"model.typo_field": 1})
    with pytest.raises(KeyError):
        config.load().role("commander")


def test_store_creates_schema_once_and_is_reopenable(tmp_path):
    path = tmp_path / "ns.db"
    s1 = Store(path)
    s1.execute("INSERT INTO incidents (id, alert_json, status, created_at, updated_at) VALUES ('i1', '{}', 'OPEN', 'now', 'now')")
    s1.close()
    s2 = Store(path)
    assert s2.one("PRAGMA user_version")[0] == SCHEMA_VERSION
    assert s2.one("SELECT status FROM incidents WHERE id = 'i1'")["status"] == "OPEN"
