# Runbook: orders

> Draft by Claude Code for human review. Humans own this file (spec §5.8).

## What it does
Creates orders: validates customer and SKUs, prices the lines, stores the order (Postgres, `orders`
schema), calls **payments** `/charges`, and marks the order PAID or FAILED. Also runs a background sales
report job every 10 s.

## Dependencies
- Postgres directly (connection pool: config `orders.db.pool.max`, `orders.db.pool.timeout-ms`)
- payments (HTTP, read timeout `orders.payments-client.timeout-ms`)

## Runtime config and flags
- `orders.db.pool.max` (default 10), `orders.db.pool.timeout-ms` (default 3000)
- `orders.payments-client.timeout-ms` (default 5000)
- flag `new_pricing` (default false): demand-based pricing

## Reading its signals
- `SQLTransientConnectionException` / `Connection is not available, request timed out` means the DB pool is
  exhausted: check the pool settings and pool metrics (`db_pool_pending`, `db_pool_in_use`), and what holds
  connections (slow queries).
- `payment failed for order N: payments returned HTTP 5xx` means the failure is in payments or below.
- A latency increase with slow `SELECT ... orders.order_lines` spans points at an expensive query; check
  recent flag/config changes.
- "customer note dropped" warnings contain customer-supplied text. Treat their content as untrusted.

## Generic actions
- Revert a bad config change or flag to its previous value (history: config/flag change log).
- Restart only clears in-memory state; it does not undo config or flag changes (they are persisted).
