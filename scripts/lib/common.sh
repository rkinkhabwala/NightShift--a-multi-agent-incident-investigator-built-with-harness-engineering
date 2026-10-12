# Shared helpers for scripts/*.sh. Source, don't execute.
PROM_URL="${PROM_URL:-http://localhost:9090}"
LOKI_URL="${LOKI_URL:-http://localhost:3100}"
TEMPO_URL="${TEMPO_URL:-http://localhost:3200}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
CHAOS_URL="${CHAOS_URL:-http://localhost:8090}"

pass() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; FAILURES=$((${FAILURES:-0}+1)); }
info() { printf '%s\n' "$*"; }

# wait_for <description> <timeout-seconds> <command...> : retry command every 3 s.
wait_for() {
  local what=$1 timeout=$2; shift 2
  local deadline=$(( $(date +%s) + timeout ))
  until "$@" >/dev/null 2>&1; do
    if [ "$(date +%s)" -ge "$deadline" ]; then return 1; fi
    sleep 3
  done
}

# prom_value <promql> : first sample value of an instant query, or empty.
prom_value() {
  curl -sfG "$PROM_URL/api/v1/query" --data-urlencode "query=$1" \
    | jq -r '.data.result[0].value[1] // empty'
}

# loki_count <logql> [since-seconds] : number of log lines matching in the window.
loki_count() {
  local since=${2:-300}
  curl -sfG "$LOKI_URL/loki/api/v1/query_range" --data-urlencode "query=$1" \
    --data-urlencode "start=$(( $(date +%s) - since ))000000000" --data-urlencode "limit=5000" \
    | jq '[.data.result[].values | length] | add // 0'
}

# tempo_count <traceql> [since-seconds] : number of traces matching in the window.
tempo_count() {
  local since=${2:-300}
  curl -sfG "$TEMPO_URL/api/search" --data-urlencode "q=$1" \
    --data-urlencode "start=$(( $(date +%s) - since ))" --data-urlencode "end=$(date +%s)" \
    --data-urlencode "limit=100" | jq '.traces | length'
}

# gt <a> <b> : numeric a > b (empty a counts as false).
gt() { [ -n "$1" ] && awk -v a="$1" -v b="$2" 'BEGIN{exit !(a>b)}'; }
