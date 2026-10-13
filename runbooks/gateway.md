# Runbook: gateway

> Draft by Claude Code for human review. Humans own this file (spec §5.8).

## What it does
Public HTTP API (`POST /api/orders`, `GET /api/orders/{id}`). Validates nothing itself and forwards to
**orders**. Client errors (4xx) from orders are passed through. Any orders failure (5xx, timeout,
connection refused) becomes **502** (or **504** on timeout) at the gateway.

## Dependencies
- orders (HTTP, read timeout `gateway.orders-client.timeout-ms`, default 8000)
- Postgres (only for its own config store)

## Reading its signals
- A gateway 5xx spike almost always mirrors a failure **downstream**. Check orders, payments and inventory
  error rates and the error spans in traces before blaming the gateway.
- Gateway log line `upstream orders failed for POST /orders: HTTP 502` names the upstream status.
- Warnings unrelated to request outcomes (key refresh, deprecation notices) are noise unless the 5xx ratio
  moves with them.

## Generic actions
- Restart: only if gateway itself is unhealthy (readiness down, its own exceptions). Rolling back or
  restarting the gateway does not fix downstream failures.
