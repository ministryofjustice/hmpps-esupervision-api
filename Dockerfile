ARG BASE_IMAGE=ghcr.io/ministryofjustice/hmpps-eclipse-temurin:21-jre-jammy
FROM --platform=$BUILDPLATFORM ${BASE_IMAGE} AS builder

ARG BUILD_NUMBER
ENV BUILD_NUMBER=${BUILD_NUMBER:-1_0_0}

WORKDIR /builder
COPY hmpps-esupervision-api-${BUILD_NUMBER}.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM ${BASE_IMAGE}

ARG BUILD_NUMBER
ENV BUILD_NUMBER=${BUILD_NUMBER:-1_0_0}

WORKDIR /app
COPY --chown=appuser:appgroup applicationinsights.json ./
COPY --chown=appuser:appgroup applicationinsights.dev.json ./
COPY --chown=appuser:appgroup applicationinsights-agent*.jar ./agent.jar
COPY --from=builder --chown=appuser:appgroup /builder/extracted/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /builder/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /builder/extracted/application/ ./

# TEMP: mitigates CVE-2026-114938 (AVX-512 Base64 intrinsic heap overflow, pre-auth RCE via JWT/Basic auth decoding).
# Remove once the base image ships Temurin 21.0.13.1 or later.
ENTRYPOINT ["java", "-XX:-UseBASE64Intrinsics", "-XX:+ExitOnOutOfMemoryError", "-XX:+AlwaysActAsServerClassMachine", "-javaagent:agent.jar", "-jar", "app.jar"]
