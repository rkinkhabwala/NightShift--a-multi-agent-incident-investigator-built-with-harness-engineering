"""Context engineering (spec §5.3): each model call's messages are assembled, not accumulated.

system  = role instructions + AGENTS.md + untrusted-data policy
user    = alert + task brief (+ incident snapshot)
then    = the agent's own working history (assistant turns and tool results), where older tool
          results are compacted to their one-line summary + evidence id once the context passes
          `compaction_ratio` of num_ctx.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from harness import guards

ROOT = Path(__file__).resolve().parents[1]


def agents_md() -> str:
    p = ROOT / "AGENTS.md"
    return p.read_text() if p.exists() else ""


@dataclass
class Turn:
    """One step of working history: the assistant message and the tool results that answered it."""
    assistant: dict[str, Any]
    results: list[dict[str, Any]] = field(default_factory=list)      # role=tool messages
    compact: list[dict[str, Any]] = field(default_factory=list)      # same, compacted
    compacted: bool = False


def compact_result(tool: str, evidence_id: str | None, content: str) -> dict[str, Any]:
    """A tool result reduced to what the agent needs to remember: what was asked and the evidence id."""
    if evidence_id is None:
        return {"role": "tool", "tool_name": tool, "content": content[:300]}
    try:
        body = json.loads(content.split("\n", 1)[1].rsplit("\n", 1)[0])
    except (IndexError, ValueError):
        body = {}
    keys = ("query", "signal", "service", "total_matching", "traces_found", "total_series", "count", "trace_id", "runbook")
    gist = {k: body[k] for k in keys if k in body}
    if body.get("top_signatures"):
        gist["top_signature"] = body["top_signatures"][0].get("signature", "")[:160]
    if body.get("series"):
        s0 = body["series"][0]
        gist["top_series"] = {k: s0[k] for k in ("series", "max", "last", "value") if k in s0}
    return {"role": "tool", "tool_name": tool,
            "content": f"[compacted; full result is evidence {evidence_id}] {json.dumps(gist, default=str)[:500]}"}


class ContextBuilder:
    def __init__(self, role_prompt: str, num_ctx: int, compaction_ratio: float, chars_per_token: float,
                 include_agents_md: bool = True):
        self.role_prompt = role_prompt
        self.num_ctx = num_ctx
        self.compaction_ratio = compaction_ratio
        self.chars_per_token = chars_per_token
        self.agents_md = agents_md() if include_agents_md else ""
        self.compactions = 0

    def system(self) -> dict[str, Any]:
        parts = [self.role_prompt.strip(), guards.UNTRUSTED_POLICY]
        if self.agents_md:
            parts.append("## System facts (AGENTS.md)\n" + self.agents_md.strip())
        return {"role": "system", "content": "\n\n".join(parts)}

    def estimate_tokens(self, messages: list[dict[str, Any]]) -> int:
        chars = sum(len(str(m.get("content", ""))) + len(json.dumps(m.get("tool_calls", []))) for m in messages)
        return int(chars / self.chars_per_token)

    def build(self, task: str, turns: list[Turn], last_prompt_tokens: int | None = None,
              reserve_tokens: int = 2048) -> list[dict[str, Any]]:
        """Messages for the next call; compacts the oldest uncompacted turns while over budget.
        The most recent turn is never compacted, so the agent always sees its latest results in full."""
        budget = int(self.num_ctx * self.compaction_ratio)

        def assemble() -> list[dict[str, Any]]:
            msgs = [self.system(), {"role": "user", "content": task}]
            for t in turns:
                msgs.append(t.assistant)
                msgs.extend(t.compact if t.compacted else t.results)
            return msgs

        msgs = assemble()
        while self.estimate_tokens(msgs) + reserve_tokens > budget:
            candidates = [t for t in turns[:-1] if not t.compacted and t.results]
            if not candidates:
                break
            candidates[0].compacted = True
            self.compactions += 1
            msgs = assemble()
        return msgs
