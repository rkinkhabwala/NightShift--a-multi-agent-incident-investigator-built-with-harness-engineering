"""Harness observability (spec §5.7): traces for every incident, agent, model call and tool call, plus
a few run metrics, exported over OTLP to the same otel-lgtm the target system uses."""

from __future__ import annotations

from typing import Any

from opentelemetry import metrics, trace
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor, SpanExporter, SimpleSpanProcessor

from harness.config import TracingConfig

_providers: dict[str, Any] = {}


def setup(cfg: TracingConfig, model: str, span_exporter: SpanExporter | None = None) -> None:
    """Idempotent. With span_exporter (tests), spans go there synchronously and metrics are not exported."""
    if _providers:
        return
    resource = Resource.create({"service.name": cfg.service_name, "nightshift.model": model})
    tp = TracerProvider(resource=resource)
    if span_exporter is not None:
        tp.add_span_processor(SimpleSpanProcessor(span_exporter))
    elif cfg.enabled:
        tp.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=f"{cfg.otlp_endpoint}/v1/traces")))
    trace.set_tracer_provider(tp)
    readers = []
    if cfg.enabled and span_exporter is None:
        readers.append(PeriodicExportingMetricReader(OTLPMetricExporter(endpoint=f"{cfg.otlp_endpoint}/v1/metrics"),
                                                     export_interval_millis=10000))
    mp = MeterProvider(resource=resource, metric_readers=readers)
    metrics.set_meter_provider(mp)
    _providers.update(tracer=tp, meter=mp)


def shutdown() -> None:
    for p in (_providers.get("tracer"), _providers.get("meter")):
        if p is not None:
            p.force_flush()
            p.shutdown()
    _providers.clear()


_meter = metrics.get_meter("nightshift.harness")
_runs = _meter.create_counter("nightshift_agent_runs", description="agent runs by role and status")
_tokens = _meter.create_counter("nightshift_tokens", description="model tokens by role and kind")
_tool_calls = _meter.create_counter("nightshift_tool_calls", description="tool calls by role")
_tool_errors = _meter.create_counter("nightshift_tool_errors", description="failed or rejected tool calls by role")
_steps = _meter.create_histogram("nightshift_agent_steps", description="steps per agent run")
_wall = _meter.create_histogram("nightshift_agent_wall_seconds", unit="s", description="wall clock per agent run")


def record_run(role: str, status: str, stats: Any) -> None:
    attrs = {"role": role, "status": status}
    _runs.add(1, attrs)
    _tokens.add(stats.prompt_tokens, {"role": role, "kind": "prompt"})
    _tokens.add(stats.completion_tokens, {"role": role, "kind": "completion"})
    _tool_calls.add(stats.tool_calls, {"role": role})
    _tool_errors.add(stats.tool_errors, {"role": role})
    _steps.record(stats.steps, {"role": role})
    _wall.record(stats.elapsed_s, {"role": role})
