#!/usr/bin/env bash
# Step 2 check: the gateway's traces, metrics and logs reach otel-lgtm via the OTel agent,
# and its stdout logs are JSON carrying trace ids.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh
FAILURES=0

info "waiting for otel-lgtm and gateway..."
wait_for lgtm 180 curl -sf "$PROM_URL/-/ready" || { fail "prometheus not ready"; exit 1; }
wait_for loki 180 curl -sf "$LOKI_URL/ready" || { fail "loki not ready"; exit 1; }
wait_for tempo 180 curl -sf "$TEMPO_URL/ready" || { fail "tempo not ready"; exit 1; }
wait_for gateway 180 curl -sf "$GATEWAY_URL/actuator/health/readiness" || { fail "gateway not ready"; exit 1; }

for _ in $(seq 20); do curl -sf "$GATEWAY_URL/api/ping" >/dev/null; done
info "sent 20 pings; waiting for export..."

q='sum(http_server_request_duration_seconds_count{service_name="gateway",http_route="/api/ping"})'
if wait_for metrics 90 sh -c "v=\$(curl -sfG '$PROM_URL/api/v1/query' --data-urlencode 'query=$q' | jq -r '.data.result[0].value[1] // 0'); [ \"\${v%.*}\" -ge 20 ]"; then
  pass "metrics: $q = $(prom_value "$q")"
else fail "metrics: $q = '$(prom_value "$q")'"; fi

if wait_for logs 90 sh -c "[ \$(curl -sfG '$LOKI_URL/loki/api/v1/query_range' --data-urlencode 'query={service_name=\"gateway\"} |= \"ping received\"' --data-urlencode limit=100 | jq '[.data.result[].values|length]|add // 0') -ge 20 ]"; then
  pass "logs: $(loki_count '{service_name="gateway"} |= "ping received"') ping lines in Loki"
else fail "logs: ping lines missing in Loki"; fi

if wait_for traces 90 sh -c "[ \$(curl -sfG '$TEMPO_URL/api/search' --data-urlencode 'q={resource.service.name=\"gateway\" && span.http.route=\"/api/ping\"}' --data-urlencode limit=50 | jq '.traces|length') -ge 1 ]"; then
  pass "traces: $(tempo_count '{resource.service.name="gateway" && span.http.route="/api/ping"}') ping traces in Tempo"
else fail "traces: no ping traces in Tempo"; fi

line=$(docker compose logs --no-log-prefix gateway 2>/dev/null | grep '"ping received"' | tail -1)
if echo "$line" | jq -e '.trace_id and .span_id and .level and .["@timestamp"]' >/dev/null 2>&1; then
  pass "stdout log is JSON with trace_id=$(echo "$line" | jq -r .trace_id)"
else fail "stdout log not JSON with trace_id: $line"; fi

tid=$(echo "$line" | jq -r '.trace_id // empty')
if [ -n "$tid" ] && wait_for trace-by-id 60 curl -sf "$TEMPO_URL/api/traces/$tid"; then
  pass "log trace_id resolves to a trace in Tempo"
else fail "log trace_id $tid not found in Tempo"; fi

[ "$FAILURES" -eq 0 ] && info "telemetry smoke: OK" || { info "telemetry smoke: $FAILURES failure(s)"; exit 1; }
