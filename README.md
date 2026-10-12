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

| URL | What |
|---|---|
| http://localhost:3000 | Grafana (login from `.env`) |
| http://localhost:8080 | gateway API |
| http://localhost:8090 | chaos controller / ops API |

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

- Spring Boot 3.5.x is used as specified. Its free OSS support ended in mid-2026, so moving
  to Boot 4 is a later decision.
