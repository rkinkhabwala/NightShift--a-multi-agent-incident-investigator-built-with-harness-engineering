#!/usr/bin/env bash
# Samples `docker stats` for the NightShift containers every INTERVAL seconds into a CSV, then
# prints per-container peak and average, and the peak total.
# usage: scripts/measure-memory.sh <duration-seconds> [interval-seconds] [out.csv]
set -uo pipefail
cd "$(dirname "$0")/.."
DURATION=${1:?duration in seconds}; INTERVAL=${2:-30}; OUT=${3:-scripts/out/memory.csv}
mkdir -p "$(dirname "$OUT")"
echo "ts,container,mem_mib,limit_mib,cpu_pct" > "$OUT"
to_mib() { awk -v v="$1" 'BEGIN{ n=v+0; u=v; gsub(/[0-9.]/,"",u);
  if(u=="GiB")n*=1024; else if(u=="KiB")n/=1024; else if(u=="B")n/=1048576; else if(u=="kB")n/=1000; printf "%.1f", n }'; }
end=$(( $(date +%s) + DURATION ))
while [ "$(date +%s)" -lt "$end" ]; do
  ts=$(date +%s)
  docker stats --no-stream --format '{{.Name}}|{{.MemUsage}}|{{.CPUPerc}}' | grep '^nightshift-' | while IFS='|' read -r name mem cpu; do
    used=${mem%% / *}; lim=${mem##* / }
    echo "$ts,${name#nightshift-},$(to_mib "$used"),$(to_mib "$lim"),${cpu%\%}" >> "$OUT"
  done
  sleep "$INTERVAL"
done
echo "container                peak MiB   avg MiB   limit MiB"
awk -F, 'NR>1 { s=$2; if($3>pk[s])pk[s]=$3; sum[s]+=$3; n[s]++; lim[s]=$4; tot[$1]+=$3 }
  END { for (s in pk) printf "%-22s %9.0f %9.0f %11.0f\n", s, pk[s], sum[s]/n[s], lim[s] | "sort";
        close("sort"); m=0; for (t in tot) if (tot[t]>m) m=tot[t]; for (t in tot) { a+=tot[t]; c++ }
        printf "TOTAL (peak sample)    %9.0f %9.0f\n", m, a/c }' "$OUT"
