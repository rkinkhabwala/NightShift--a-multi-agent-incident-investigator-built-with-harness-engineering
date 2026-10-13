# Runbook: payments

> Draft by Claude Code for human review. Humans own this file (spec §5.8).

## What it does
`POST /charges`: applies the loyalty discount, runs a risk rule (declines large amounts with 402), reserves
stock in **inventory**, then records the charge in Postgres (`payments` schema).

## Dependencies
- inventory (HTTP, read timeout `payments.inventory-client.timeout-ms`, default 2000)
- Postgres (through the network proxy `pg-payments`)

## Reading its signals
- 402 = declined by the risk rule (normal background), 409 = out of stock (normal background).
- 502 `inventory_unavailable` / 504: the inventory call failed or timed out. Compare inventory's own latency
  and errors with payments' timeout setting before deciding which side is wrong.
- New exception types inside payments itself that start at a deploy point at the new version.
- Connection errors to Postgres from payments only (other services fine) point at payments' DB connectivity.

## Generic actions
- Roll back to the previous version if errors start at a deploy.
- Revert a config change if errors start at it.
- DB connectivity problems: escalate to the database/network owner; restarts do not help.
