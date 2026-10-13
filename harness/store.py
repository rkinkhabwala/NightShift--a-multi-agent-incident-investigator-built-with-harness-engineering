"""SQLite storage: incidents, agent runs, evidence, eval runs and results (spec §4)."""

from __future__ import annotations

import json
import sqlite3
import threading
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1

SCHEMA = """
CREATE TABLE IF NOT EXISTS incidents (
    id           TEXT PRIMARY KEY,
    alert_json   TEXT NOT NULL,
    status       TEXT NOT NULL,              -- OPEN | INVESTIGATING | REPORTED | FAILED | RESOLVED
    report_json  TEXT,
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS agent_runs (
    id                TEXT PRIMARY KEY,
    incident_id       TEXT NOT NULL REFERENCES incidents(id),
    agent             TEXT NOT NULL,
    status            TEXT NOT NULL,         -- RUNNING | FINISHED | BUDGET_EXCEEDED | FINISH_INVALID | ERROR
    steps             INTEGER NOT NULL DEFAULT 0,
    model_calls       INTEGER NOT NULL DEFAULT 0,
    prompt_tokens     INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    tool_calls        INTEGER NOT NULL DEFAULT 0,
    tool_errors       INTEGER NOT NULL DEFAULT 0,
    write_attempts    INTEGER NOT NULL DEFAULT 0,
    started_at        TEXT NOT NULL,
    finished_at       TEXT,
    error             TEXT,
    transcript_json   TEXT
);
CREATE TABLE IF NOT EXISTS evidence (
    id           TEXT PRIMARY KEY,           -- ev-0001, unique per incident (prefixed in storage)
    incident_id  TEXT NOT NULL REFERENCES incidents(id),
    seq          INTEGER NOT NULL,
    tool         TEXT NOT NULL,
    args_json    TEXT NOT NULL,
    summary_json TEXT NOT NULL,              -- what the model saw (shaped, redacted)
    raw_json     TEXT NOT NULL,              -- full result, for verification and citation checks
    created_at   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS evidence_incident ON evidence (incident_id, seq);
CREATE VIRTUAL TABLE IF NOT EXISTS past_incidents USING fts5(
    incident_id UNINDEXED, title, root_cause, remediation, report
);
CREATE TABLE IF NOT EXISTS eval_runs (
    id           TEXT PRIMARY KEY,
    started_at   TEXT NOT NULL,
    finished_at  TEXT,
    git_sha      TEXT NOT NULL,
    model        TEXT NOT NULL,
    ollama_version TEXT,
    config_hash  TEXT NOT NULL,
    config_json  TEXT NOT NULL,
    scenarios    TEXT NOT NULL,
    runs         INTEGER NOT NULL,
    label        TEXT
);
CREATE TABLE IF NOT EXISTS eval_results (
    eval_run_id  TEXT NOT NULL REFERENCES eval_runs(id),
    scenario     TEXT NOT NULL,
    run_index    INTEGER NOT NULL,
    incident_id  TEXT,
    status       TEXT NOT NULL,              -- SCORED | ERROR
    scores_json  TEXT,
    error        TEXT,
    created_at   TEXT NOT NULL,
    PRIMARY KEY (eval_run_id, scenario, run_index)
);
"""


def now() -> str:
    return datetime.now(UTC).isoformat()


class Store:
    """Thin, thread-safe wrapper over one SQLite file."""

    def __init__(self, path: str | Path):
        self.path = str(path)
        if self.path != ":memory:":
            Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._db = sqlite3.connect(self.path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.execute("PRAGMA journal_mode = WAL" if self.path != ":memory:" else "PRAGMA journal_mode = MEMORY")
        self._migrate()

    def _migrate(self) -> None:
        with self._lock:
            version = self._db.execute("PRAGMA user_version").fetchone()[0]
            if version > SCHEMA_VERSION:
                raise RuntimeError(f"database schema v{version} is newer than this code (v{SCHEMA_VERSION})")
            self._db.executescript(SCHEMA)
            self._db.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
            self._db.commit()

    def execute(self, sql: str, params: tuple | dict = ()) -> sqlite3.Cursor:
        with self._lock:
            cur = self._db.execute(sql, params)
            self._db.commit()
            return cur

    def one(self, sql: str, params: tuple | dict = ()) -> sqlite3.Row | None:
        with self._lock:
            return self._db.execute(sql, params).fetchone()

    def all(self, sql: str, params: tuple | dict = ()) -> list[sqlite3.Row]:
        with self._lock:
            return self._db.execute(sql, params).fetchall()

    def close(self) -> None:
        self._db.close()


def dumps(value: Any) -> str:
    return json.dumps(value, default=str, sort_keys=True)
