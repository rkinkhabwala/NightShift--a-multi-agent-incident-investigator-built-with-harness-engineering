# One image for every target service; SERVICE (set in compose) picks the jar.
# Build context: target/
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY common/pom.xml common/
COPY gateway/pom.xml gateway/
COPY orders/pom.xml orders/
COPY payments/pom.xml payments/
COPY inventory/pom.xml inventory/
COPY chaos-controller/pom.xml chaos-controller/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline -DexcludeArtifactIds=common || true
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
 && mkdir /out \
 && for m in gateway orders payments inventory chaos-controller; do cp $m/target/app.jar /out/$m.jar; done

FROM eclipse-temurin:21.0.12.1_1-jre-alpine
ARG OTEL_AGENT_VERSION=2.32.0
ARG OTEL_AGENT_SHA256=f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82
RUN apk add --no-cache curl \
 && curl -sSfL -o /opt/opentelemetry-javaagent.jar \
    https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar \
 && echo "${OTEL_AGENT_SHA256}  /opt/opentelemetry-javaagent.jar" | sha256sum -c - \
 && addgroup -S app && adduser -S app -G app \
 && mkdir -p /releases && chown app:app /releases
COPY --from=build /out/ /app/
COPY docker/entrypoint.sh /entrypoint.sh
USER app
WORKDIR /app
ENTRYPOINT ["/bin/sh", "/entrypoint.sh"]
