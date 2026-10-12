#!/usr/bin/env python3
"""Generates telemetry/grafana/dashboards/nightshift-overview.json (run after editing)."""
import json
import pathlib

PROM = {"type": "prometheus", "uid": "prometheus"}
LOKI = {"type": "loki", "uid": "loki"}
TEMPO = {"type": "tempo", "uid": "tempo"}
PG = {"type": "grafana-postgresql-datasource", "uid": "nightshift-ops"}
SVC = 'service_name=~"$service"'
APP_ROUTES = 'http_route!~"/actuator.*|/admin.*|/internal.*"'

panels = []
y = 0


def ts(title, exprs, unit, x, w=12, h=8, legend="{{service_name}}", desc=""):
    panels.append({
        "type": "timeseries", "title": title, "description": desc,
        "gridPos": {"x": x, "y": y, "w": w, "h": h}, "datasource": PROM,
        "fieldConfig": {"defaults": {"unit": unit, "custom": {"lineWidth": 2, "fillOpacity": 8}}, "overrides": []},
        "options": {"legend": {"displayMode": "table", "placement": "right", "calcs": ["lastNotNull", "max"]},
                    "tooltip": {"mode": "multi", "sort": "desc"}},
        "targets": [{"refId": chr(65 + i), "expr": e, "legendFormat": l if isinstance(e, str) else legend}
                    for i, (e, l) in enumerate(exprs)],
    })


def row(title):
    global y
    panels.append({"type": "row", "title": title, "collapsed": False, "gridPos": {"x": 0, "y": y, "w": 24, "h": 1}, "panels": []})
    y += 1


row("Traffic and errors")
ts("Request rate", [(f'sum by (service_name) (rate(http_server_request_duration_seconds_count{{{SVC},{APP_ROUTES}}}[1m]))', "{{service_name}}")], "reqps", 0)
ts("5xx error rate", [(f'sum by (service_name) (rate(http_server_request_duration_seconds_count{{{SVC},{APP_ROUTES},http_response_status_code=~"5.."}}[1m]))', "{{service_name}}")], "reqps", 12)
y += 8
ts("5xx ratio", [(f'sum by (service_name) (rate(http_server_request_duration_seconds_count{{{SVC},{APP_ROUTES},http_response_status_code=~"5.."}}[2m])) / sum by (service_name) (rate(http_server_request_duration_seconds_count{{{SVC},{APP_ROUTES}}}[2m]))', "{{service_name}}")], "percentunit", 0, desc="HighErrorRate fires above 5 %")
ts("p95 latency", [(f'histogram_quantile(0.95, sum by (service_name, le) (rate(http_server_request_duration_seconds_bucket{{{SVC},{APP_ROUTES}}}[1m])))', "{{service_name}}")], "s", 12, desc="HighLatencyP95 fires above 1 s")
y += 8
row("Runtime")
ts("JVM heap used", [(f'sum by (service_name) (jvm_memory_used_bytes{{{SVC},jvm_memory_type="heap"}})', "{{service_name}} used"),
                     (f'sum by (service_name) (jvm_memory_limit_bytes{{{SVC},jvm_memory_type="heap"}})', "{{service_name}} max")], "bytes", 0)
ts("Restarts (process starts, 10 m window)", [(f'sum by (service_name) (changes(max by (service_name) (process_start_time_seconds{{{SVC}}})[10m:15s]))', "{{service_name}}")], "short", 12,
   desc="Includes deploy restarts. ServiceRestarting fires at >= 2 restarts of one version in 10 m.")
y += 8
ts("DB pool: connections in use / pending", [(f'sum by (service_name) (db_client_connections_usage{{{SVC},state="used"}})', "{{service_name}} used"),
                                            (f'sum by (service_name) (db_client_connections_pending_requests{{{SVC}}})', "{{service_name}} pending")], "short", 0)
ts("Downstream call p95 (HTTP client)", [(f'histogram_quantile(0.95, sum by (service_name, server_address, le) (rate(http_client_request_duration_seconds_bucket{{{SVC},server_address!="chaos-controller"}}[1m])))', "{{service_name}} -> {{server_address}}")], "s", 12)
y += 8
row("Changes, logs and traces")
panels.append({
    "type": "table", "title": "Recent deploys, config and flag changes", "datasource": PG,
    "gridPos": {"x": 0, "y": y, "w": 24, "h": 8},
    "targets": [{"refId": "A", "format": "table", "rawQuery": True, "editorMode": "code", "rawSql":
                 "SELECT deployed_at AS \"time\", 'deploy' AS kind, service, 'version' AS key, previous_version AS old, version AS new, initiated_by AS who, status AS detail FROM ops.deploys WHERE $__timeFilter(deployed_at) "
                 "UNION ALL SELECT changed_at, lower(kind), service, key, old_value, new_value, changed_by, reason FROM ops.settings_history WHERE $__timeFilter(changed_at) "
                 "ORDER BY 1 DESC LIMIT 100"}],
    "options": {"showHeader": True, "sortBy": [{"displayName": "time", "desc": True}]},
})
y += 8
panels.append({
    "type": "logs", "title": "Errors and warnings", "datasource": LOKI, "gridPos": {"x": 0, "y": y, "w": 24, "h": 10},
    "targets": [{"refId": "A", "expr": '{service_name=~"$service"} | detected_level=~"error|warn"'}],
    "options": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending", "enableLogDetails": True},
})
y += 10
panels.append({
    "type": "table", "title": "Recent error traces", "datasource": TEMPO, "gridPos": {"x": 0, "y": y, "w": 24, "h": 8},
    "targets": [{"refId": "A", "queryType": "traceql", "query": '{resource.service.name=~"$service" && status=error}', "limit": 20, "tableType": "traces"}],
})

annotation_sql = {
    "Deploys": ("#F2495C", "SELECT deployed_at AS time, service || ' ' || coalesce(previous_version, '?') || ' -> ' || version || ' (' || initiated_by || ')' AS text, 'deploy,' || service AS tags FROM ops.deploys WHERE $__timeFilter(deployed_at)"),
    "Config changes": ("#FF9830", "SELECT changed_at AS time, service || ' ' || key || ': ' || coalesce(old_value, '?') || ' -> ' || new_value || ' by ' || changed_by AS text, 'config,' || service AS tags FROM ops.settings_history WHERE kind = 'CONFIG' AND $__timeFilter(changed_at)"),
    "Flag changes": ("#B877D9", "SELECT changed_at AS time, service || ' flag ' || key || ': ' || coalesce(old_value, '?') || ' -> ' || new_value || ' by ' || changed_by AS text, 'flag,' || service AS tags FROM ops.settings_history WHERE kind = 'FLAG' AND $__timeFilter(changed_at)"),
}
annotations = [{"builtIn": 1, "datasource": {"type": "grafana", "uid": "-- Grafana --"}, "enable": True, "hide": True,
                "iconColor": "rgba(0, 211, 255, 1)", "name": "Annotations & Alerts", "type": "dashboard"}]
for name, (color, sql) in annotation_sql.items():
    annotations.append({"name": name, "datasource": PG, "enable": True, "iconColor": color,
                        "target": {"refId": "Anno", "format": "table", "rawQuery": True, "editorMode": "code", "rawSql": sql}})

dashboard = {
    "uid": "nightshift-overview", "title": "NightShift - Target overview", "tags": ["nightshift"],
    "timezone": "browser", "schemaVersion": 39, "version": 1, "refresh": "10s",
    "time": {"from": "now-30m", "to": "now"},
    "templating": {"list": [{
        "name": "service", "label": "Service", "type": "custom", "multi": True, "includeAll": True,
        "allValue": "gateway|orders|payments|inventory",
        "query": "gateway,orders,payments,inventory",
        "current": {"selected": True, "text": ["All"], "value": ["$__all"]},
    }]},
    "annotations": {"list": annotations},
    "panels": panels,
}
out = pathlib.Path(__file__).resolve().parents[2] / "telemetry/grafana/dashboards/nightshift-overview.json"
out.write_text(json.dumps(dashboard, indent=2) + "\n")
print(f"wrote {out} ({len(panels)} panels)")
