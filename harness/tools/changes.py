"""Read tools over the ops API: list_deploys, diff_deploy, get_config, get_feature_flags."""

from __future__ import annotations

from typing import Literal

from pydantic import Field

from harness.tools.base import Tool, ToolContext, ToolInput, ToolOutput

Service = Literal["gateway", "orders", "payments", "inventory"]
SERVICES: tuple[str, ...] = ("gateway", "orders", "payments", "inventory")


def deploy_row(d: dict) -> dict:
    return {"deploy_id": d.get("id"), "service": d.get("service"), "version": d.get("version"),
            "previous_version": d.get("previousVersion"), "at": d.get("deployedAt"),
            "completed_at": d.get("completedAt"), "status": d.get("status"), "initiated_by": d.get("initiatedBy"),
            "changelog": d.get("changelog")}


def change_row(service: str, kind: str, h: dict) -> dict:
    return {"service": service, "kind": kind, "key": h.get("key"), "old": h.get("oldValue"), "new": h.get("newValue"),
            "at": h.get("changedAt"), "changed_by": h.get("changedBy"), "reason": h.get("reason") or None}


class ListDeploys(Tool):
    name = "list_deploys"
    description = "List deploys in the last N minutes (all services or one), newest first, with version change and changelog."

    class Input(ToolInput):
        service: Service | None = None
        minutes: int = Field(default=120, ge=1, le=7 * 24 * 60)

    async def run(self, args: Input, ctx: ToolContext) -> ToolOutput:
        start, _ = ctx.window(args.minutes)
        params = {"since": start.isoformat().replace("+00:00", "Z"), "limit": 50}
        if args.service:
            params["service"] = args.service
        data = await ctx.http.get("ops", "/ops/deploys", params)
        rows = [deploy_row(d) for d in data]
        return ToolOutput({"window_minutes": args.minutes, "service": args.service, "deploys": rows, "count": len(rows)}, data)


class DiffDeploy(Tool):
    name = "diff_deploy"
    description = "Show what one deploy changed: service, previous and new version, changelog, who and when."

    class Input(ToolInput):
        deploy_id: int = Field(ge=1)

    async def run(self, args: Input, ctx: ToolContext) -> ToolOutput:
        data = await ctx.http.get("ops", f"/ops/deploys/{args.deploy_id}")
        return ToolOutput(deploy_row(data), data)


class _SettingsTool(Tool):
    kind: str = "config"

    class Input(ToolInput):
        service: Service
        minutes: int = Field(default=120, ge=1, le=7 * 24 * 60)

    async def run(self, args: Input, ctx: ToolContext) -> ToolOutput:
        start, _ = ctx.window(args.minutes)
        data = await ctx.http.get("ops", f"/ops/services/{args.service}/{self.kind}",
                                  {"since": start.isoformat().replace("+00:00", "Z")})
        history = [change_row(args.service, self.kind, h) for h in data.get("history", [])]
        return ToolOutput({"service": args.service, "current": data.get("current", {}),
                           "changes_in_window": history, "window_minutes": args.minutes}, data)


class GetConfig(_SettingsTool):
    name = "get_config"
    description = "Current runtime config of a service and its config changes in the last N minutes."
    kind = "config"


class GetFeatureFlags(_SettingsTool):
    name = "get_feature_flags"
    description = "Current feature flags of a service and its flag changes in the last N minutes."
    kind = "flags"
