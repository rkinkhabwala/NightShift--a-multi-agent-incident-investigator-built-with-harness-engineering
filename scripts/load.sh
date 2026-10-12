#!/usr/bin/env bash
# Control the k6 load generator: pause | resume | stop | start | status
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-status}" in
  pause)  docker compose --profile target pause k6 && echo "load paused" ;;
  resume) docker compose --profile target unpause k6 && echo "load resumed" ;;
  stop)   docker compose --profile target stop k6 ;;
  start)  docker compose --profile target up -d k6 ;;
  status) docker inspect -f '{{.State.Status}}{{if .State.Paused}} (paused){{end}}' nightshift-k6-1 2>/dev/null || echo "not running" ;;
  *) echo "usage: $0 pause|resume|stop|start|status" >&2; exit 2 ;;
esac
