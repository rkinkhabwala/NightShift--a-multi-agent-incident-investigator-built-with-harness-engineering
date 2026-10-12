#!/usr/bin/env bash
# Phase 1 acceptance: for each scenario, confirm baseline, inject it through the chaos API,
# check that its expected signal shows up in Prometheus / Loki / Tempo (plus deploys, config and
# flag history, alerts), reset it, and confirm the system returns to baseline.
#
# usage: scripts/verify-scenarios.sh [S01 S02 ...]      (default: all scenarios)
# The checks come from each scenario's `verify` block (evals/scenarios/*.yaml).
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh
# Every HTTP call gets a timeout so a stalled host cannot hang the loop.
curl() { command curl --max-time 30 "$@"; }

GRACE=${GRACE:-180}            # extra seconds allowed beyond settle_seconds for signals to appear
BASELINE_TIMEOUT=${BASELINE_TIMEOUT:-480}
OUT=scripts/out; mkdir -p "$OUT"
declare -a RESULTS=()

ids=("$@")
if [ $# -eq 0 ]; then
  ids=()
  while read -r i; do ids+=("$i"); done < <(curl -sf "$CHAOS_URL/chaos/scenarios" | jq -r '.[].id')
fi

now() { date +%s; }
iso() { date -u -r "$1" +%Y-%m-%dT%H:%M:%SZ; }

# ---- baseline -------------------------------------------------------------------------------
# Prints the reasons the system is not at baseline; returns 0 when it is.
baseline_problems() {
  local p=()
  [ "$(curl -sf "$CHAOS_URL/chaos/status" | jq -r .phase)" = IDLE ] || p+=("chaos controller not idle")
  local svc
  svc=$(curl -sf "$CHAOS_URL/ops/services") || p+=("ops API down")
  for s in gateway orders payments inventory; do
    local v b st
    v=$(echo "$svc" | jq -r --arg s $s '.[$s].version'); b=$(echo "$svc" | jq -r --arg s $s '.[$s].baseline')
    st=$(echo "$svc" | jq -r --arg s $s '.[$s].deployStatus')
    [ "$v" = "$b" ] && [ "$st" = COMPLETED ] || p+=("$s at $v ($st), baseline $b")
    curl -sf "http://localhost:$(svc_port $s)/actuator/health/readiness" >/dev/null || p+=("$s not ready")
    for kind in config flags; do
      local diff
      diff=$(jq -n --argjson cur "$(curl -sf "http://localhost:$(svc_port $s)/admin/$kind")" \
                    --argjson def "$(curl -sf "http://localhost:$(svc_port $s)/admin/$kind/definitions")" \
             '[$def[] | select(($cur[.key]) != .defaultValue) | .key] | join(",")' -r 2>/dev/null)
      [ -z "$diff" ] || p+=("$s $kind not default: $diff")
    done
  done
  local tox
  for proxy in redis pg-payments pg-inventory; do
    tox=$(curl -sf "localhost:8474/proxies/$proxy" | jq -r '"\(.enabled) \(.toxics | length)"')
    [ "$tox" = "true 0" ] || p+=("toxiproxy $proxy: enabled/toxics = $tox")
  done
  local r5 p95 firing
  r5=$(prom_value 'sum(rate(http_server_request_duration_seconds_count{service_name="gateway",http_response_status_code=~"5.."}[1m])) / sum(rate(http_server_request_duration_seconds_count{service_name="gateway"}[1m]))')
  awk -v v="${r5:-0}" 'BEGIN{exit !(v < 0.02)}' || p+=("gateway 5xx ratio ${r5}")
  p95=$(prom_value 'histogram_quantile(0.95, sum by (le) (rate(http_server_request_duration_seconds_bucket{service_name="gateway",http_route=~"/api/orders.*"}[1m])))')
  awk -v v="${p95:-0}" 'BEGIN{exit !(v < 0.5)}' || p+=("gateway p95 ${p95}s")
  firing=$(curl -sf "$PROM_URL/api/v1/alerts" | jq -r '[.data.alerts[] | select(.state=="firing") | "\(.labels.alertname)/\(.labels.service_name)"] | join(",")')
  [ -z "$firing" ] || p+=("alerts firing: $firing")
  local rate
  rate=$(prom_value 'sum(rate(http_server_request_duration_seconds_count{service_name="gateway",http_route=~"/api/orders.*"}[1m]))')
  awk -v v="${rate:-0}" 'BEGIN{exit !(v > 2)}' || p+=("no load (gateway ${rate:-0} req/s; is k6 paused?)")
  [ ${#p[@]} -eq 0 ] && return 0
  printf '%s\n' "${p[@]}"; return 1
}

wait_baseline() {   # $1 = label, $2 = timeout
  local deadline=$(( $(now) + $2 )) last=""
  while true; do
    if last=$(baseline_problems); then return 0; fi
    [ "$(now)" -ge "$deadline" ] && { echo "$last" | sed "s/^/    [$1] /"; return 1; }
    sleep 10
  done
}

# ---- signal checks ----------------------------------------------------------------------------
# Each check prints one line "ok|fail <description>". $1 = scenario JSON, $2 = inject epoch.
run_checks() {
  local sc=$1 t0=$2 since=$(( $(now) - $2 + 30 ))
  local v; v=$(echo "$sc" | jq '.verify')

  echo "$v" | jq -c '.prom[]?' | while read -r c; do
    local q op val got; q=$(echo "$c" | jq -r .query); op=$(echo "$c" | jq -r .op); val=$(echo "$c" | jq -r .value)
    got=$(prom_value "$q")
    if [ -n "$got" ] && [ "$got" != NaN ] && awk -v a="$got" -v b="$val" -v op="$op" 'BEGIN{exit !((op==">"&&a>b)||(op==">="&&a>=b)||(op=="<"&&a<b)||(op=="<="&&a<=b))}'; then
      echo "ok   prom $op $val (got $(printf '%.3g' "$got")): ${q:0:90}"
    else echo "fail prom $op $val (got ${got:-none}): ${q:0:90}"; fi
  done
  echo "$v" | jq -c '.loki[]?' | while read -r c; do
    local q min got; q=$(echo "$c" | jq -r .query); min=$(echo "$c" | jq -r .min_lines)
    got=$(loki_count "$q" "$since")
    [ "${got:-0}" -ge "$min" ] && echo "ok   loki >= $min lines (got $got): $q" || echo "fail loki >= $min lines (got ${got:-0}): $q"
  done
  echo "$v" | jq -c '.tempo[]?' | while read -r c; do
    local q min got; q=$(echo "$c" | jq -r .query); min=$(echo "$c" | jq -r .min_traces)
    got=$(tempo_count "$q" "$since")
    [ "${got:-0}" -ge "$min" ] && echo "ok   tempo >= $min traces (got $got): $q" || echo "fail tempo >= $min traces (got ${got:-0}): $q"
  done
  echo "$v" | jq -c '.deploys[]?' | while read -r c; do
    local s ver; s=$(echo "$c" | jq -r .service); ver=$(echo "$c" | jq -r .version)
    curl -sfG "$CHAOS_URL/ops/deploys" --data-urlencode "service=$s" --data-urlencode "since=$(iso "$t0")" \
      | jq -e --arg v "$ver" 'any(.[]; .version == $v and .status == "COMPLETED")' >/dev/null \
      && echo "ok   deploy registry: $s $ver" || echo "fail deploy registry: $s $ver not found"
  done
  for kind in config flag; do
    echo "$v" | jq -c ".${kind}_history[]?" | while read -r c; do
      local s k nv path; s=$(echo "$c" | jq -r .service); k=$(echo "$c" | jq -r .key); nv=$(echo "$c" | jq -r .new_value)
      path=$([ $kind = config ] && echo config || echo flags)
      curl -sfG "http://localhost:$(svc_port "$s")/admin/$path/history" --data-urlencode "since=$(iso "$t0")" \
        | jq -e --arg k "$k" --arg nv "$nv" 'any(.[]; .key == $k and .newValue == $nv)' >/dev/null \
        && echo "ok   $kind history: $s $k -> $nv" || echo "fail $kind history: $s $k -> $nv not found"
    done
  done
  local ar; ar=$(echo "$v" | jq -c '.alert_received // empty')
  if [ -n "$ar" ]; then
    curl -sf "$CHAOS_URL/ops/alerts" | jq -e --argjson a "$ar" 'any(.[]; .alertname == $a.alertname and .service == $a.service and .status == "firing")' >/dev/null \
      && echo "ok   alert received: $(echo "$ar" | jq -r '.alertname + "/" + .service')" || echo "fail alert not received: $ar"
  fi
  echo "$sc" | jq -r '.expectedAlerts[]?' | while read -r a; do
    curl -sf "$CHAOS_URL/ops/alerts" | jq -e --arg a "$a" 'any(.[]; .alertname == $a and .status == "firing")' >/dev/null \
      && echo "ok   alert $a fired and reached the receiver" || echo "fail alert $a has not reached the receiver"
  done
}

# ---- main loop ----------------------------------------------------------------------------
scripts/load.sh resume >/dev/null 2>&1 || true
for id in "${ids[@]}"; do
  sc=$(curl -sf "$CHAOS_URL/chaos/scenarios/$id") || { RESULTS+=("$id FAIL unknown scenario"); continue; }
  settle=$(echo "$sc" | jq -r '.verify.settle_seconds')
  info ""; info "=== $id: $(echo "$sc" | jq -r .title)"

  info "  baseline before inject..."
  if ! wait_baseline "$id pre" "$BASELINE_TIMEOUT"; then
    curl -sf -X POST "$CHAOS_URL/chaos/reset-all" >/dev/null
    wait_baseline "$id pre" "$BASELINE_TIMEOUT" || { RESULTS+=("$id FAIL not at baseline before inject"); continue; }
  fi
  curl -sf -X DELETE "$CHAOS_URL/ops/alerts" >/dev/null

  t0=$(now)
  st=$(command curl -s -m 900 -X POST "$CHAOS_URL/chaos/scenarios/$id/inject")
  if [ "$(echo "$st" | jq -r .phase)" != ACTIVE ]; then
    info "  inject failed: $(echo "$st" | jq -c '[.steps[] | select(.ok|not)]')"
    RESULTS+=("$id FAIL inject"); command curl -s -m 900 -X POST "$CHAOS_URL/chaos/scenarios/$id/reset" >/dev/null; continue
  fi
  info "  injected in $(( $(now) - t0 ))s; checking signals (settle ${settle}s, up to +${GRACE}s)..."

  deadline=$(( t0 + settle + GRACE )); sleep 20
  while true; do
    checks=$(run_checks "$sc" "$t0")
    if ! echo "$checks" | grep -q '^fail'; then break; fi
    [ "$(now)" -ge "$deadline" ] && break
    sleep 15
  done
  echo "$checks" | sed 's/^ok  /  PASS/; s/^fail/  FAIL/'
  echo "$checks" > "$OUT/$id-checks.txt"
  signal_ok=$(echo "$checks" | grep -q '^fail' && echo no || echo yes)
  info "  signals after $(( $(now) - t0 ))s: $([ $signal_ok = yes ] && echo OK || echo MISSING)"

  t1=$(now)
  st=$(command curl -s -m 900 -X POST "$CHAOS_URL/chaos/scenarios/$id/reset")
  info "  reset in $(( $(now) - t1 ))s ($(echo "$st" | jq -r .phase)); waiting for baseline..."
  if wait_baseline "$id post" "$BASELINE_TIMEOUT"; then base_ok=yes; info "  back at baseline after $(( $(now) - t1 ))s"; else base_ok=no; fi

  if [ $signal_ok = yes ] && [ $base_ok = yes ]; then RESULTS+=("$id PASS")
  else RESULTS+=("$id FAIL signal=$signal_ok baseline=$base_ok"); fi
done

info ""; info "=== summary"
printf '  %s\n' "${RESULTS[@]}" | tee "$OUT/summary.txt"
! printf '%s\n' "${RESULTS[@]}" | grep -q FAIL
