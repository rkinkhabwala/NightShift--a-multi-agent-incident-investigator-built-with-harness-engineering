#!/bin/sh
# Read-only role for Grafana's ops datasource (annotations, change table).
set -eu
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'grafana_reader') THEN
    CREATE ROLE grafana_reader LOGIN PASSWORD '${GRAFANA_DB_PASSWORD:?GRAFANA_DB_PASSWORD not set}';
  END IF;
END \$\$;
GRANT USAGE ON SCHEMA ops TO grafana_reader;
GRANT SELECT ON ops.deploys, ops.settings_history TO grafana_reader;
SQL
