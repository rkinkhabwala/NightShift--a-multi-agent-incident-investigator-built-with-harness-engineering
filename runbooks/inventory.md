# Runbook: inventory

> Draft by Claude Code for human review. Humans own this file (spec §5.8).

## What it does
`POST /reservations`: checks stock in a Redis cache, takes stock and records the reservation in Postgres
(`inventory` schema). A background job restocks SKUs every minute.

## Dependencies
- Redis (stock cache, through the network proxy `redis`; command timeout 5 s)
- Postgres (through the network proxy `pg-inventory`)

## Reading its signals
- Slow spans on Redis `GET`/`SET` or on Postgres statements with no code change mean the dependency or the
  network path is slow; check whether other services' calls to the same store are slow too.
- Errors that start at a deploy point at the new version.
- Check heap and restarts as well as errors: resource exhaustion shows up as rising memory and process restarts.

## Generic actions
- Roll back to the previous version if errors start at a deploy.
- Restart as a mitigation for heap exhaustion (it will come back if the leak remains).
- Slow Redis/Postgres network: escalate to the infrastructure owner; do not roll back application code.
