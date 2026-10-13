"""The generalist's five tools (spec §5.9: at most 5 per agent). Each one routes to the real read
tools; parameters are simple enums so a small model rarely has to write PromQL/LogQL/TraceQL itself,
while a raw query is still accepted."""

from __future__ import annotations

import asyncio
from typing import Literal

from pydantic import Field, model_validator

from harness.tools.base import Tool, ToolContext, ToolInput, ToolOutput
from harness.tools.changes import SERVICES, Service, change_row, deploy_row
from harness.tools.knowledge import ReadRunbook
from harness.tools.telemetry import MAX_MINUTES, LokiQuery, PromQuery, PromRangeQuery, TempoGetTrace, TempoSearch

APP_ROUTES = 'http_route!~"/actuator.*|/admin.*|/internal.*"'

# Logback, java.util.logging (Tomcat) and OTel severities all reach Loki as detected_level.
LEVELS = {"error": "(?i)error|severe|fatal|critical", "warn": "(?i)warn|warning", "info": "(?i)info"}

Signal = Literal["error_rate", "request_rate", "latency_p95", "status_codes", "heap_used_ratio", "restarts",
                 "db_pool_pending", "db_pool_in_use", "downstream_latency_p95", "alerts"]


def svc_matcher(service: str | None) -> str:
    return f'service_name="{service}"' if service else 'service_name=~"gateway|orders|payments|inventory"'


def promql_for(signal: str, service: str | None) -> str:
    s = svc_matcher(service)
    count = "http_server_request_duration_seconds_count"
    return {
        "error_rate": (f'(sum by (service_name) (rate({count}{{{s},{APP_ROUTES},http_response_status_code=~"5.."}}[1m])) '
                       f'or sum by (service_name) (rate({count}{{{s},{APP_ROUTES}}}[1m])) * 0) '
                       f'/ sum by (service_name) (rate({count}{{{s},{APP_ROUTES}}}[1m]))'),
        "request_rate": f"sum by (service_name) (rate({count}{{{s},{APP_ROUTES}}}[1m]))",
        "latency_p95": (f"histogram_quantile(0.95, sum by (service_name, le) "
                        f"(rate(http_server_request_duration_seconds_bucket{{{s},{APP_ROUTES}}}[1m])))"),
        "status_codes": f"sum by (service_name, http_response_status_code) (rate({count}{{{s},{APP_ROUTES}}}[1m]))",
        "heap_used_ratio": (f'sum by (service_name) (jvm_memory_used_bytes{{{s},jvm_memory_type="heap"}}) '
                            f'/ sum by (service_name) (jvm_memory_limit_bytes{{{s},jvm_memory_type="heap"}})'),
        "restarts": f"max by (service_name) (changes(process_start_time_seconds{{{s}}}[10m]))",
        "db_pool_pending": f"sum by (service_name) (db_client_connections_pending_requests{{{s}}})",
        "db_pool_in_use": f'sum by (service_name) (db_client_connections_usage{{{s},state="used"}})',
        "downstream_latency_p95": (f'histogram_quantile(0.95, sum by (service_name, server_address, le) '
                                   f'(rate(http_client_request_duration_seconds_bucket{{{s},server_address!="chaos-controller"}}[1m])))'),
        "alerts": f'ALERTS{{alertstate="firing"{"," + s if service else ""}}}',
    }[signal]


class QueryMetrics(Tool):
    name = "query_metrics"
    description = ("Metrics per service over the last N minutes. Pick a signal (error_rate = 5xx ratio, request_rate, "
                   "latency_p95 in seconds, status_codes, heap_used_ratio, restarts, db_pool_pending, db_pool_in_use, "
                   "downstream_latency_p95, alerts) and optionally a service; or pass raw promql. "
                   "Returns min/max/mean/last and the main change point per series.")

    class Input(ToolInput):
        signal: Signal | None = None
        service: Service | None = None
        promql: str | None = Field(default=None, description="raw PromQL instead of signal")
        minutes: int = Field(default=30, ge=1, le=MAX_MINUTES)

        @model_validator(mode="after")
        def one_of(self):
            if not self.signal and not self.promql:
                raise ValueError("give a signal or promql")
            return self

    async def run(self, args: Input, ctx: ToolContext) -> ToolOutput:
        q = args.promql or promql_for(args.signal, args.service)
        if args.signal == "alerts":
            out = await PromQuery().run(PromQuery.Input(promql=q), ctx)
        else:
            out = await PromRangeQuery().run(PromRangeQuery.Input(promql=q, minutes=args.minutes), ctx)
        return ToolOutput({"signal": args.signal, "service": args.service, **out.summary}, out.raw)


class QueryLogs(Tool):
    name = "query_logs"
    description = ("Logs over the last N minutes, optionally for one service, one level (error/warn/info) and lines "
                   "containing a text; or pass raw logql. Returns counts, top message signatures (with exception "
                   "types) and sample lines.")

    class Input(ToolInput):
        service: Service | None = None
        level: Literal["error", "warn", "info"] | None = None
        contains: str | None = Field(default=None, max_length=200)
        logql: str | None = Field(default=None, description="raw LogQL instead of the filters")
        minutes: int = Field(default=30, ge=1, le=MAX_MINUTES)

    async def run(self, args: Input, ctx: ToolContext) -> ToolOutput:
        if args.logql:
            q = args.logql
        else:
            q = "{" + svc_matcher(args.service) + "}"
            if args.level:
                q += f' | detected_level=~"{LEVELS[args.level]}"'
            if args.contains:
                q += ' |= "' + args.contains.replace("\\", "\\\\").replace('"', '\\"') + '"'
        return await LokiQuery().run(LokiQuery.Input(logql=q, minutes=args.minutes), ctx)


class QueryTraces(Tool):
    name = "query_traces"
    description = ("Traces over the last N minutes. Search by service, errors_only and/or min_duration_ms (or raw "
                   "traceql); or pass trace_id to get one trace's spans, errors and slowest spans.")

    class Input(ToolInput):
        trace_id: str | None = Field(default=None, pattern=r"^[0-9a-fA-F]{16,32}$")
        service: Service | None = None
        errors_only: bool = False
        min_duration_ms: int | None = Field(default=None, ge=1)
        traceql: str | None = None
        minutes: int = Field(default=30, ge=1, le=MAX_MINUTES)

    async def run(self, args: Input, ctx: ToolContext) -> ToolOutput:
        if args.trace_id:
            return await TempoGetTrace().run(TempoGetTrace.Input(trace_id=args.trace_id), ctx)
        if args.traceql:
            q = args.traceql
        else:
            parts = [f'resource.service.name="{args.service}"' if args.service else
                     'resource.service.name=~"gateway|orders|payments|inventory"']
            if args.errors_only:
                parts.append("status=error")
            if args.min_duration_ms:
                parts.append(f"duration > {args.min_duration_ms}ms")
            q = "{" + " && ".join(parts) + "}"
        return await TempoSearch().run(TempoSearch.Input(traceql=q, minutes=args.minutes), ctx)


class ListChanges(Tool):
    name = "list_changes"
    description = ("Everything that changed in the last N minutes: deploys (with changelog), config changes and "
                   "feature-flag changes, for all services or one, as one timeline newest first.")

    class Input(ToolInput):
        service: Service | None = None
        minutes: int = Field(default=120, ge=1, le=7 * 24 * 60)

    async def run(self, args: Input, ctx: ToolContext) -> ToolOutput:
        start, _ = ctx.window(args.minutes)
        since = start.isoformat().replace("+00:00", "Z")
        services = [args.service] if args.service else list(SERVICES)
        deploy_params = {"since": since, "limit": 50, **({"service": args.service} if args.service else {})}

        async def settings(svc: str, kind: str):
            return svc, kind, await ctx.http.get("ops", f"/ops/services/{svc}/{kind}", {"since": since})

        deploys, *settings_results = await asyncio.gather(
            ctx.http.get("ops", "/ops/deploys", deploy_params),
            *[settings(s, k) for s in services for k in ("config", "flags")])
        timeline = [{"kind": "deploy", **deploy_row(d)} for d in deploys]
        current: dict[str, dict] = {}
        for svc, kind, data in settings_results:
            current.setdefault(svc, {})[kind] = data.get("current", {})
            timeline += [change_row(svc, "config" if kind == "config" else "flag", h) for h in data.get("history", [])]
        timeline.sort(key=lambda r: str(r.get("at") or ""), reverse=True)
        return ToolOutput({"window_minutes": args.minutes, "service": args.service, "changes": timeline[:60],
                           "count": len(timeline), "current_settings": current},
                          {"deploys": deploys, "settings": [r[2] for r in settings_results]})


GENERALIST_TOOLS: list[Tool] = [QueryLogs(), QueryMetrics(), QueryTraces(), ListChanges(), ReadRunbook()]
