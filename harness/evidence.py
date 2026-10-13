"""Evidence board: every tool result is stored in full under an id the agent can cite (spec §5.2)."""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from harness.store import Store, dumps, now


@dataclass(frozen=True)
class Evidence:
    id: str
    tool: str
    args: dict[str, Any]
    summary: dict[str, Any]
    raw: Any


class EvidenceBoard:
    def __init__(self, store: Store, incident_id: str):
        self.store = store
        self.incident_id = incident_id
        row = store.one("SELECT max(seq) AS m FROM evidence WHERE incident_id = ?", (incident_id,))
        self._seq = (row["m"] or 0) if row else 0

    def add(self, tool: str, args: dict[str, Any], summary: dict[str, Any], raw: Any) -> str:
        self._seq += 1
        evidence_id = f"ev-{self._seq:04d}"
        self.store.execute(
            "INSERT INTO evidence (id, incident_id, seq, tool, args_json, summary_json, raw_json, created_at) VALUES (?,?,?,?,?,?,?,?)",
            (f"{self.incident_id}/{evidence_id}", self.incident_id, self._seq, tool, dumps(args), dumps(summary), dumps(raw), now()))
        return evidence_id

    def get(self, evidence_id: str) -> Evidence | None:
        row = self.store.one("SELECT * FROM evidence WHERE id = ?", (f"{self.incident_id}/{evidence_id}",))
        if row is None:
            return None
        return Evidence(evidence_id, row["tool"], json.loads(row["args_json"]), json.loads(row["summary_json"]),
                        json.loads(row["raw_json"]))

    def ids(self) -> list[str]:
        return [r["id"].split("/", 1)[1] for r in
                self.store.all("SELECT id FROM evidence WHERE incident_id = ? ORDER BY seq", (self.incident_id,))]

    def all(self) -> list[Evidence]:
        return [e for e in (self.get(i) for i in self.ids()) if e is not None]
