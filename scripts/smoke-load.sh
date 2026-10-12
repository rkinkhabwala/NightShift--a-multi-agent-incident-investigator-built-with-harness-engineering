#!/usr/bin/env bash
# Step 4 check: k6 drives roughly K6_RATE req/s through the gateway, with a small 4xx baseline
# and (without faults) almost no 5xx; pause stops the traffic and resume brings it back.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh
FAILURES=0
RATE=${K6_RATE:-8}

GW_RATE='sum(rate(http_server_request_duration_seconds_count{service_name="gateway",http_route=~"/api/orders.*"}[1m]))'
GW_4XX='sum(rate(http_server_request_duration_seconds_count{service_name="gateway",http_response_status_code=~"4.."}[2m]))'
GW_5XX_RATIO='sum(rate(http_server_request_duration_seconds_count{service_name="gateway",http_response_status_code=~"5.."}[2m])) / sum(rate(http_server_request_duration_seconds_count{service_name="gateway"}[2m]))'

scripts/load.sh resume >/dev/null 2>&1 || true
info "waiting for a full minute of load..."
wait_for "load" 180 sh -c "v=\$(curl -sfG '$PROM_URL/api/v1/query' --data-urlencode 'query=$GW_RATE' | jq -r '.data.result[0].value[1] // 0'); awk -v v=\$v -v r=$RATE 'BEGIN{exit !(v > r*0.7)}'"
sleep 30
r=$(prom_value "$GW_RATE")
awk -v v="$r" -v t="$RATE" 'BEGIN{exit !(v > t*0.7 && v < t*1.3)}' && pass "gateway order traffic ${r} req/s (target $RATE)" || fail "gateway rate ${r} (target $RATE)"
e4=$(prom_value "$GW_4XX"); gt "$e4" 0 && pass "background 4xx present (${e4}/s)" || fail "no background 4xx"
e5=$(prom_value "$GW_5XX_RATIO"); e5=${e5:-0}
awk -v v="$e5" 'BEGIN{exit !(v < 0.02)}' && pass "baseline 5xx ratio ${e5} < 2 %" || fail "baseline 5xx ratio ${e5}"

scripts/load.sh pause >/dev/null
info "paused; waiting for the rate to drain..."
if wait_for "drain" 150 sh -c "v=\$(curl -sfG '$PROM_URL/api/v1/query' --data-urlencode 'query=$GW_RATE' | jq -r '.data.result[0].value[1] // 0'); awk -v v=\$v 'BEGIN{exit !(v < 0.2)}'"; then
  pass "pause stops traffic ($(prom_value "$GW_RATE") req/s)"
else fail "rate still $(prom_value "$GW_RATE") after pause"; fi

scripts/load.sh resume >/dev/null
if wait_for "resume" 150 sh -c "v=\$(curl -sfG '$PROM_URL/api/v1/query' --data-urlencode 'query=$GW_RATE' | jq -r '.data.result[0].value[1] // 0'); awk -v v=\$v -v r=$RATE 'BEGIN{exit !(v > r*0.5)}'"; then
  pass "resume restores traffic ($(prom_value "$GW_RATE") req/s)"
else fail "traffic did not come back after resume"; fi

[ "$FAILURES" -eq 0 ] && info "load smoke: OK" || { info "load smoke: $FAILURES failure(s)"; exit 1; }
