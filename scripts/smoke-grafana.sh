#!/usr/bin/env bash
# Step 7 check: the provisioned dashboard exists, and every panel query and annotation query
# returns data through Grafana itself (Prometheus, Loki, Tempo and the ops Postgres datasource).
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh
[ -f .env ] && set -a && source .env && set +a
AUTH="${GRAFANA_ADMIN_USER:-admin}:${GRAFANA_ADMIN_PASSWORD:-admin}"
FAILURES=0

dash=$(curl -sf -m 20 -u "$AUTH" "$GRAFANA_URL/api/dashboards/uid/nightshift-overview") || { fail "dashboard not provisioned"; exit 1; }
pass "dashboard '$(echo "$dash" | jq -r .dashboard.title)' provisioned in folder '$(echo "$dash" | jq -r .meta.folderTitle)'"
home=$(curl -sf -m 20 -u "$AUTH" "$GRAFANA_URL/api/dashboards/home" | jq -r '.redirectUri // .dashboard.title')
case "$home" in *nightshift-target-overview*) pass "it is the home dashboard ($home)" ;; *) fail "home dashboard is '$home'" ;; esac

# query <datasource-json> <target-json> : number of rows/values returned for one query
query() {
  local body
  body=$(jq -n --argjson ds "$1" --argjson t "$2" \
    '{from: "now-30m", to: "now", queries: [($t + {datasource: $ds, intervalMs: 15000, maxDataPoints: 200}
       | (.expr |= (if . then gsub("\\$service"; "gateway|orders|payments|inventory") else . end))
       | (.query |= (if . then gsub("\\$service"; "gateway|orders|payments|inventory") else . end)))]}')
  curl -sf -m 60 -u "$AUTH" -H 'Content-Type: application/json' "$GRAFANA_URL/api/ds/query" -d "$body" \
    | jq '[.results[].frames[]? | .data.values[0]? // [] | length] | add // 0'
}

while read -r p; do
  title=$(echo "$p" | jq -r .title); ds=$(echo "$p" | jq -c .datasource)
  total=0
  while read -r t; do n=$(query "$ds" "$t"); total=$(( total + ${n:-0} )); done < <(echo "$p" | jq -c '.targets[]')
  [ "$total" -gt 0 ] && pass "panel '$title': $total values" || fail "panel '$title' returned no data"
done < <(echo "$dash" | jq -c '.dashboard.panels[] | select(.type != "row")')

while read -r a; do
  name=$(echo "$a" | jq -r .name)
  n=$(query "$(echo "$a" | jq -c .datasource)" "$(echo "$a" | jq -c '.target + {refId: "A"}' | sed 's/\$__timeFilter(\([a-z_]*\))/\1 > now() - interval '"'"'2 days'"'"'/g')")
  [ "${n:-0}" -gt 0 ] && pass "annotation '$name': $n events" || fail "annotation '$name' returned no events"
done < <(echo "$dash" | jq -c '.dashboard.annotations.list[] | select(.datasource.uid == "nightshift-ops")')

[ "$FAILURES" -eq 0 ] && info "grafana smoke: OK" || { info "grafana smoke: $FAILURES failure(s)"; exit 1; }
