"""Read tools over Loki, Prometheus and Tempo: loki_query, prom_query, prom_range_query,
tempo_search, tempo_get_trace."""

from __future__ import annotations

from pydantic import Field, field_validator

from harness import shaping
from harness.tools.base import Tool, ToolContext, ToolInput, ToolOutput

MAX_MINUTES = 360


def _ns(dt) -> str:
    return str(int(dt.timestamp() * 1e9))


class LokiQuery(Tool):
    name = "loki_query"
    description = "Run a LogQL log query over the last N minutes. Returns counts, top message signatures and sample lines."

    class Input(ToolInput):
        logql: str = Field(description='LogQL log selector, e.g. {service_name="payments"} |= "Exception"')
        minutes: int = Field(default=30, ge=1, le=MAX_MINUTES)
        limit: int = Field(default=300, ge=1, le=500)

        @field_validator("logql")
        @classmethod
        def selector(cls, v: str) -> str:
            v = v.strip()
            if not v.startswith("{"):
                raise ValueError('must start with a stream selector like {service_name="orders"}')
            if len(v) > 600:
                raise ValueError("query too long")
            return v

    async def run(self, args: Input, ctx: ToolContext) -> ToolOutput:
        start, end = ctx.window(args.minutes)
        data = await ctx.http.get("loki", "/loki/api/v1/query_range", {
            "query": args.logql, "start": _ns(start), "end": _ns(end), "limit": args.limit, "direction": "backward"})
        entries = []
        for stream in data.get("data", {}).get("result", []):
            labels = stream.get("stream", {})
            for ts, line in stream.get("values", []):
                entries.append({"ts": int(ts), "line": line, "labels": labels})
        entries.sort(key=lambda e: e["ts"], reverse=True)
        total = None
        try:
            count = await ctx.http.get("loki", "/loki/api/v1/query", {
                "query": f"sum(count_over_time({args.logql} [{args.minutes}m]))", "time": _ns(end)})
            res = count.get("data", {}).get("result", [])
            total = int(float(res[0]["value"][1])) if res else 0
        except Exception:  # noqa: BLE001 - the count is a nicety; the lines still answer the question
            total = None
        cfg = ctx.settings.tools
        summary = {"query": args.logql, "window_minutes": args.minutes,
                   **shaping.summarize_logs(entries, total, cfg.log_sample_lines, cfg.log_top_signatures)}
        return ToolOutput(summary, {"entries": len(entries), "streams": data.get("data", {}).get("result", [])})


class PromQuery(Tool):
    name = "prom_query"
    description = "Evaluate a PromQL expression at the current time. Returns one value per series."

    class Input(ToolInput):
        promql: str = Field(description="PromQL expression")

    async def run(self, args: Input, ctx: ToolContext) -> ToolOutput:
        end = ctx.clock()
        data = await ctx.http.get("prometheus", "/api/v1/query", {"query": args.promql, "time": end.timestamp()})
        result = data.get("data", {}).get("result", [])
        return ToolOutput({"query": args.promql, "at": shaping.iso(end.timestamp()),
                           **shaping.summarize_instant(result, ctx.settings.tools.metric_max_series)}, data)


class PromRangeQuery(Tool):
    name = "prom_range_query"
    description = "Evaluate a PromQL expression over the last N minutes. Returns per-series min/max/mean/p95/last and the main change point."

    class Input(ToolInput):
        promql: str = Field(description="PromQL expression")
        minutes: int = Field(default=30, ge=1, le=MAX_MINUTES)
        step_seconds: int | None = Field(default=None, ge=5, le=3600)

    async def run(self, args: Input, ctx: ToolContext) -> ToolOutput:
        start, end = ctx.window(args.minutes)
        step = args.step_seconds or max(15, args.minutes * 60 // 60)
        data = await ctx.http.get("prometheus", "/api/v1/query_range", {
            "query": args.promql, "start": start.timestamp(), "end": end.timestamp(), "step": step})
        result = data.get("data", {}).get("result", [])
        return ToolOutput({"query": args.promql, "window_minutes": args.minutes, "step_seconds": step,
                           **shaping.summarize_range(result, ctx.settings.tools.metric_max_series)}, data)


def _visible(trace: dict) -> bool:
    """Hides chaos-controller plumbing (PUT /internal/switches) from agents (Phase 1 caveat)."""
    return "/internal/" not in str(trace.get("rootTraceName", ""))


class TempoSearch(Tool):
    name = "tempo_search"
    description = "Search traces with TraceQL over the last N minutes. Returns counts per root service and the slowest matching traces with ids."

    class Input(ToolInput):
        traceql: str = Field(description='TraceQL, e.g. {resource.service.name="payments" && status=error}')
        minutes: int = Field(default=30, ge=1, le=MAX_MINUTES)
        limit: int = Field(default=20, ge=1, le=100)

        @field_validator("traceql")
        @classmethod
        def braces(cls, v: str) -> str:
            v = v.strip()
            if not v.startswith("{"):
                raise ValueError("TraceQL must start with { ... }")
            return v

    async def run(self, args: Input, ctx: ToolContext) -> ToolOutput:
        start, end = ctx.window(args.minutes)
        data = await ctx.http.get("tempo", "/api/search", {
            "q": args.traceql, "start": int(start.timestamp()), "end": int(end.timestamp()), "limit": min(100, args.limit * 3)})
        traces = [t for t in data.get("traces", []) if _visible(t)]
        summary = {"query": args.traceql, "window_minutes": args.minutes,
                   **shaping.summarize_search(traces, min(args.limit, ctx.settings.tools.trace_max_results))}
        return ToolOutput(summary, data)


class TempoGetTrace(Tool):
    name = "tempo_get_trace"
    description = "Fetch one trace by id. Returns per-service span counts, error spans with exceptions, and the slowest spans."

    class Input(ToolInput):
        trace_id: str = Field(pattern=r"^[0-9a-fA-F]{16,32}$")

    async def run(self, args: Input, ctx: ToolContext) -> ToolOutput:
        data = await ctx.http.get("tempo", f"/api/traces/{args.trace_id}")
        spans = shaping.flatten_trace(data)
        spans = [s for s in spans if "/internal/" not in str(s["attributes"].get("http.route", ""))]
        return ToolOutput(shaping.summarize_trace(args.trace_id, spans), data)
