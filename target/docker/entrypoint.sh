#!/bin/sh
# Starts one target service. The running version comes from the shared releases volume
# (written by the chaos controller's deploy API), falling back to the baseline version.
set -eu
: "${SERVICE:?SERVICE must be set}"
VERSION="${BASELINE_VERSION:-0.0.0}"
if [ -s "/releases/${SERVICE}.version" ]; then
  VERSION="$(tr -d '[:space:]' < "/releases/${SERVICE}.version")"
fi
export APP_VERSION="${VERSION}"
export OTEL_SERVICE_NAME="${SERVICE}"
# A fixed instance id keeps one series per service across restarts, so counter resets
# (and therefore restarts) are visible in Prometheus.
export OTEL_RESOURCE_ATTRIBUTES="service.version=${VERSION},service.instance.id=${SERVICE},deployment.environment.name=local${OTEL_RESOURCE_ATTRIBUTES_EXTRA:+,${OTEL_RESOURCE_ATTRIBUTES_EXTRA}}"
echo "starting ${SERVICE} version ${VERSION}"
exec java ${JAVA_OPTS:-} -javaagent:/opt/opentelemetry-javaagent.jar -jar "/app/${SERVICE}.jar"
