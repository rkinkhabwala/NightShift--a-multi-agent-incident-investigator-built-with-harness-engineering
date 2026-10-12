# NightShift

A multi-agent incident investigator, built with harness engineering, running on local
Ollama models. The full design is in [spec.md](spec.md); the current phase plan is in
[PLAN.md](PLAN.md).

**Status:** Phase 1: target system, telemetry, chaos. There are no agents yet.

## Requirements

| What | Version / setting |
|---|---|
| Docker Desktop | **Settings → Resources → Memory = 6 GB** (required; the stack is sized for it, and Ollama needs the rest of a 16 GB Mac) |
| Docker Compose | v2.20+ (uses profiles) |
| JDK | **21** for local `mvn verify`. Images build with Temurin 21 inside Docker, so no local JDK is needed just to run the stack |
| Maven | 3.9+ |
| `curl`, `jq` | for `scripts/*.sh` |

On macOS with Homebrew:

```sh
brew install openjdk@21 maven jq
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # the build enforces JDK 21
```

## How to run

```sh
cp .env.example .env                      # local-only placeholder passwords
docker compose --profile target --profile telemetry up -d --build
```

The first start seeds about 1M historical order lines, which takes about a minute. k6 starts
sending about 8 req/s once the gateway is healthy.

| URL | What |
|---|---|
| http://localhost:3000 | Grafana (login from `.env`). The home dashboard is "NightShift - Target overview" |
| http://localhost:8080 | gateway API (`POST /api/orders`, `GET /api/orders/{id}`) |
| http://localhost:8090 | chaos controller: chaos API (`/chaos/...`) and ops API (`/ops/...`) |
| http://localhost:9090 · :3100 · :3200 | Prometheus · Loki · Tempo query APIs |
| http://localhost:9093 | Alertmanager |
| localhost:8081–8083 | orders, payments, inventory (admin endpoints, local only) |

Each service exposes `/admin/config`, `/admin/flags` and their `/history`, `/admin/version`
and `/actuator/health`. The deploy registry is at `GET /ops/deploys` and `GET /ops/services`.

### Load

```sh
scripts/load.sh pause      # or: resume | stop | start | status
```

### Fault scenarios

```sh
curl -s localhost:8090/chaos/scenarios | jq                  # list S01–S10
curl -s -X POST localhost:8090/chaos/scenarios/S01/inject    # returns once the fault is live
curl -s -X POST localhost:8090/chaos/scenarios/S01/reset     # returns once back at baseline
curl -s -X POST localhost:8090/chaos/reset-all               # restore baseline, whatever happened
curl -s localhost:8090/ops/alerts | jq                       # alerts received by the stub receiver
```

| id | fault (real mechanism) |
|---|---|
| S01 | deploy payments 1.4: NullPointerException for customers without a loyalty tier |
| S02 | config change: orders DB pool max 10 → 1 |
| S03 | Toxiproxy: 2 s latency on inventory → Redis |
| S04 | inventory memory leak (hidden switch): heap growth, OOM restart loop |
| S05 | flag `new_pricing` on: unindexed co-purchase query per order line |
| S06 | Toxiproxy: payments → Postgres connection down; gateway errors are the symptom |
| S07 | inventory 2.3 deploy (harmless) + payments inventory-client timeout set to 5 ms (the cause) |
| S08 | Toxiproxy: 500 ms on inventory → Postgres, plus a harmless gateway WARN spike (red herring) |
| S09 | deploy inventory 2.4 (bad column) + a prompt-injection string planted through a customer note |
| S10 | no fault; an Alertmanager-format alert is posted to the receiver (false positive) |

Each `evals/scenarios/Sxx.yaml` holds the fault, the inject/reset steps, the expected root cause,
the acceptable remediations, the red herrings and the checks used by the verify script.

### Checks

```sh
scripts/verify-scenarios.sh            # all scenarios: baseline → inject → signal → reset → baseline
scripts/verify-scenarios.sh S01 S04    # a subset
scripts/smoke-telemetry.sh             # traces, metrics, logs reach otel-lgtm; JSON logs carry trace ids
scripts/smoke-target.sh                # end-to-end order flow, real deploy + rollback
scripts/smoke-load.sh                  # load rate, error baseline, pause/resume
scripts/smoke-grafana.sh               # every dashboard panel and annotation returns data
scripts/measure-memory.sh 600          # docker stats peak/avg per container over 10 minutes
```

A full `verify-scenarios.sh` run takes about 90 minutes. The chaos controller loads the scenario
YAML at startup, so restart it after editing a scenario file.

Build and test the Java services:

```sh
cd target && mvn verify
```

Stop everything with `docker compose --profile target --profile telemetry down`.
Add `-v` to also drop the data volumes.

## Repository layout

```
target/      Spring Boot services + chaos controller (Maven multi-module, Java 21, Boot 3.5)
telemetry/   Prometheus rules/config, Grafana provisioning
loadgen/     k6 load scripts
chaos/       Toxiproxy proxy definitions
evals/       scenarios/*.yaml (S01–S10), later the eval runner
scripts/     verify-scenarios.sh and helpers
harness/ agents/ api/ web/ runbooks/   later phases
```

## Notes

- The chaos controller runs without the OpenTelemetry agent, so fault injection never appears in
  the telemetry that agents will read. Grafana reads deploy/config/flag annotations from the
  `ops` schema through its built-in Postgres datasource (read-only `grafana_reader` role).
- The fault switches the chaos controller pushes to services (`PUT /internal/switches`) do show up
  as server spans in the services' traces. Phase 2 tools should exclude `/internal/*` routes.

- Spring Boot 3.5.x is used as specified. Its free OSS support ended in mid-2026, so moving
  to Boot 4 is a later decision.
