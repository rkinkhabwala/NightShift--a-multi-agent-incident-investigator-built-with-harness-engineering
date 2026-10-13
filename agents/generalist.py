"""The Phase 2 single agent: one generalist with five read tools (spec §10 Phase 2)."""

from __future__ import annotations

from pathlib import Path

from agents.schemas import Report

ROLE = "generalist"
PROMPT = (Path(__file__).parent / "prompts" / "generalist.md").read_text()
REPORT_MODEL = Report
BRIEF = ("Find the root cause of this alert. Work out which component is actually failing (not just where the "
         "alert fired), what started it, and whether a single safe action would fix it.")
