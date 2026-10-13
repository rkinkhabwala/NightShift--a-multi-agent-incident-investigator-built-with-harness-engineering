"""Progressive-disclosure tools (spec §5.3): read_runbook, search_past_incidents."""

from __future__ import annotations

import re

from pydantic import Field

from harness.tools.base import Tool, ToolContext, ToolInput, ToolOutput


class ReadRunbook(Tool):
    name = "read_runbook"
    description = "Read the runbook for a service (or 'index' to list runbooks)."

    class Input(ToolInput):
        name: str = Field(description="service name, e.g. payments, or 'index'", pattern=r"^[a-z0-9_-]{1,40}$")

    async def run(self, args: Input, ctx: ToolContext) -> ToolOutput:
        available = sorted(p.stem for p in ctx.runbooks_dir.glob("*.md"))
        if args.name == "index" or args.name not in available:
            return ToolOutput({"available_runbooks": available,
                               **({"error": f"no runbook named {args.name!r}"} if args.name != "index" else {})}, None)
        text = (ctx.runbooks_dir / f"{args.name}.md").read_text()
        return ToolOutput({"runbook": args.name, "content": text[:6000], "truncated": len(text) > 6000}, text)


class SearchPastIncidents(Tool):
    name = "search_past_incidents"
    description = "Full-text search over resolved past incidents (root cause confirmed by a human)."

    class Input(ToolInput):
        query: str = Field(min_length=2, max_length=200)
        limit: int = Field(default=3, ge=1, le=10)

    async def run(self, args: Input, ctx: ToolContext) -> ToolOutput:
        terms = " OR ".join(f'"{t}"' for t in re.findall(r"[A-Za-z0-9_.]{2,}", args.query)[:8])
        rows = ctx.store.all("SELECT incident_id, title, root_cause, remediation, snippet(past_incidents, 2, '[', ']', '…', 12) AS s "
                             "FROM past_incidents WHERE past_incidents MATCH ? ORDER BY rank LIMIT ?",
                             (terms or '""', args.limit)) if terms else []
        hits = [{"incident_id": r["incident_id"], "title": r["title"], "root_cause": r["root_cause"],
                 "remediation": r["remediation"], "match": r["s"]} for r in rows]
        return ToolOutput({"query": args.query, "matches": hits, "count": len(hits)}, hits)
