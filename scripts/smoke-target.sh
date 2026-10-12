#!/usr/bin/env bash
# Step 3 check: the order flow works end to end, one trace spans all four services, the
# deploy registry / config / flags endpoints work, and a real deploy (payments 1.4) changes
# behaviour and can be rolled back.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh
FAILURES=0

for s in gateway orders payments inventory; do
  wait_for "$s" 180 curl -sf "http://localhost:$(svc_port $s)/actuator/health/readiness" || fail "$s not ready"
done

code=$(order 21 SKU-003 2 2>/dev/null); [ "$code" = 201 ] && pass "order through gateway -> 201" || fail "order -> $code"
code=$(order 21 SKU-OOS 1 2>/dev/null); [ "$code" = 409 ] && pass "out-of-stock -> 409" || fail "out-of-stock -> $code"
code=$(order 21 SKU-999 1 2>/dev/null); [ "$code" = 422 ] && pass "unknown sku -> 422" || fail "unknown sku -> $code"

q='{resource.service.name="gateway"} >> {resource.service.name="orders"} >> {resource.service.name="payments"} >> {resource.service.name="inventory"}'
if wait_for "trace" 90 sh -c "[ \$(curl -sfG '$TEMPO_URL/api/search' --data-urlencode 'q=$q' --data-urlencode limit=5 | jq '.traces|length') -ge 1 ]"; then
  pass "a trace spans gateway > orders > payments > inventory"
else fail "no trace spanning all four services"; fi

n=$(curl -sf "$CHAOS_URL/ops/services" | jq '[.[] | select(.deployStatus=="COMPLETED")] | length')
[ "$n" = 4 ] && pass "deploy registry knows 4 services" || fail "deploy registry: $n services"

for s in gateway orders payments inventory; do
  p=$(svc_port $s)
  curl -sf "http://localhost:$p/admin/config" >/dev/null && curl -sf "http://localhost:$p/admin/flags" >/dev/null \
    && curl -sf "http://localhost:$p/admin/config/history" >/dev/null || fail "$s admin endpoints"
done
pass "config/flags/history endpoints answer on all services"

curl -sf -X PUT -H 'Content-Type: application/json' -d '{"value":"true","changedBy":"smoke","reason":"smoke test"}' \
  localhost:8081/admin/flags/new_pricing >/dev/null
curl -sf -X PUT -H 'Content-Type: application/json' -d '{"value":"false","changedBy":"smoke","reason":"smoke test"}' \
  localhost:8081/admin/flags/new_pricing >/dev/null
h=$(curl -sf localhost:8081/admin/flags/history | jq -r '[.[] | select(.changedBy=="smoke")] | length')
[ "${h:-0}" -ge 2 ] && pass "flag changes recorded in history ($h)" || fail "flag history: $h"

line=$(docker compose logs --no-log-prefix orders 2>/dev/null | grep '"trace_id"' | tail -1)
echo "$line" | jq -e '.trace_id and .service_name? // true' >/dev/null 2>&1 && pass "orders logs are JSON with trace_id" || fail "orders log: $line"

info "deploying payments 1.4..."
if deploy payments 1.4 smoke; then
  pass "payments now on 1.4 (registry + health)"
  sleep 3
  c1=$(order 1 SKU-001 1 2>/dev/null)   # customer 1 has no loyalty tier
  c8=$(order 8 SKU-001 1 2>/dev/null)   # customer 8 has a tier
  [ "$c1" = 502 ] && pass "1.4: no-tier customer -> 502 at gateway" || fail "1.4: no-tier customer -> $c1"
  [ "$c8" = 201 ] && pass "1.4: tier customer -> 201" || fail "1.4: tier customer -> $c8"
  wait_for "npe in loki" 60 sh -c "[ \$(curl -sfG '$LOKI_URL/loki/api/v1/query_range' --data-urlencode 'query={service_name=\"payments\"} |= \"NullPointerException\"' | jq '[.data.result[].values|length]|add // 0') -ge 1 ]" \
    && pass "NullPointerException visible in Loki" || fail "NPE not in Loki"
else fail "deploy payments 1.4 did not complete"; fi

deploy payments 1.3 smoke && pass "rolled back to payments 1.3" || fail "rollback to 1.3"
code=$(order 1 SKU-001 1 2>/dev/null); [ "$code" = 201 ] && pass "1.3: no-tier customer -> 201 again" || fail "after rollback -> $code"

[ "$FAILURES" -eq 0 ] && info "target smoke: OK" || { info "target smoke: $FAILURES failure(s)"; exit 1; }
