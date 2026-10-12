# AGENTS.md — facts about the target system for NightShift agents

Edited by humans only. Every line added after Phase 1 must cite the eval failure or
incident that motivated it (spec §5.8).

## Topology
- Request path: `gateway → orders → payments → inventory`.
- Stores: Postgres (orders, payments, inventory schemas; `ops` schema for config/flag history),
  Redis (inventory stock cache, reached through Toxiproxy).
- Telemetry: every service exports traces, metrics, logs over OTLP (OpenTelemetry Java agent)
  to `grafana/otel-lgtm`. Label `service_name` identifies the service.

## Change sources
- Deploys: chaos-controller ops API `GET /ops/deploys`.
- Config and feature flags: each service's `GET /admin/config/history`, `GET /admin/flags/history`.
