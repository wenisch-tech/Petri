# syntax=docker/dockerfile:1.27@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e

# Alpine JDK: Petri runs approved tests in this container (PETRI_TESTS_EXECUTOR=local) and lets
# agents install further Alpine packages into an unprivileged toolchain prefix on /app/data.
FROM eclipse-temurin:25-jdk-alpine

COPY scripts/alpine-runtime.sh /tmp/alpine-runtime.sh
RUN sh /tmp/alpine-runtime.sh && rm -f /tmp/alpine-runtime.sh

WORKDIR /app

ARG BUILD_DATE
ARG BUILD_VERSION
ARG BUILD_REVISION

LABEL org.opencontainers.image.title="Petri" \
      org.opencontainers.image.description="Self-hosted agentic coding harness, multi-agent orchestrator and development environment" \
      org.opencontainers.image.url="https://github.com/wenisch-tech/Petri" \
      org.opencontainers.image.source="https://github.com/wenisch-tech/Petri" \
      org.opencontainers.image.documentation="https://github.com/wenisch-tech/Petri/tree/main/docs" \
      org.opencontainers.image.authors="JFWenisch" \
      org.opencontainers.image.licenses="AGPL-3.0" \
      org.opencontainers.image.vendor="JFWenisch" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.revision="${BUILD_REVISION}" \
      org.opencontainers.image.created="${BUILD_DATE}"

ENV PETRI_TESTS_EXECUTOR=local \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=20.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/urandom"

COPY --chown=petri:petri target/petri-*.jar /app/app.jar
COPY --chown=petri:petri skills/ /app/skills/

USER petri
VOLUME ["/app/data"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
