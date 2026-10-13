"""Which tools each role gets. Least privilege per role is enforced in code (spec §5.2)."""

from __future__ import annotations

from harness.tools.base import Permission, Tool, ToolSet
from harness.tools.changes import DiffDeploy, GetConfig, GetFeatureFlags, ListDeploys
from harness.tools.composite import GENERALIST_TOOLS
from harness.tools.knowledge import ReadRunbook, SearchPastIncidents
from harness.tools.telemetry import LokiQuery, PromQuery, PromRangeQuery, TempoGetTrace, TempoSearch

# All read tools named in spec §5.2. Phase 3 specialists draw their (<= 5) tools from here.
READ_TOOLS: dict[str, Tool] = {t.name: t for t in [
    LokiQuery(), PromQuery(), PromRangeQuery(), TempoSearch(), TempoGetTrace(),
    ListDeploys(), DiffDeploy(), GetConfig(), GetFeatureFlags(), ReadRunbook(), SearchPastIncidents()]}

ROLE_TOOLS: dict[str, list[Tool]] = {
    "generalist": GENERALIST_TOOLS,
}


def toolset(role: str) -> ToolSet:
    if role not in ROLE_TOOLS:
        raise KeyError(f"no tools defined for role {role!r}")
    return ToolSet(role, ROLE_TOOLS[role], allowed={Permission.READ})
