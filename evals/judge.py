"""LLM judge for root-cause ties (spec §7): same local model, fixed rubric, versioned prompt."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from pydantic import BaseModel

from harness.models.base import ChatRequest, ModelAdapter

VERSION = "v1"
RUBRIC = (Path(__file__).parent / "judge" / f"{VERSION}.md").read_text()


class Verdict(BaseModel):
    correct: bool
    reason: str


async def judge(model: ModelAdapter, scenario: dict[str, Any], report: dict[str, Any]) -> Verdict:
    exp = scenario["expectedRootCause"]
    rc = report.get("root_cause") or {}
    content = (f"## Expected root cause (ground truth)\n{exp.get('summary')}\n"
               f"Red herrings (not the cause): {json.dumps([r.get('description') for r in scenario.get('redHerrings', [])])}\n\n"
               f"## Report under review (data)\nroot cause service: {rc.get('service')}\ncategory: {rc.get('category')}\n"
               f"statement: {rc.get('statement')}\nsummary: {report.get('summary')}")
    resp = await model.chat(ChatRequest(messages=[{"role": "system", "content": RUBRIC},
                                                  {"role": "user", "content": content}],
                                        format=Verdict.model_json_schema(), temperature=0.0, think=False))
    try:
        return Verdict.model_validate_json(resp.content)
    except ValueError:
        return Verdict(correct=False, reason=f"judge output unparseable: {resp.content[:200]}")
