# syntax=docker/dockerfile:1.27@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e

# petri-confine: a tiny static Landlock helper that confines terminal and test commands to the
# worktree for any UID, without root or setuid.
FROM alpine:3.24 AS confine
RUN apk add --no-cache gcc musl-dev
COPY docker/petri-confine.c /src/petri-confine.c
RUN gcc -O2 -static -o /petri-confine /src/petri-confine.c

# Trivy: the vulnerability scanner behind the vulnerability-scan skill. It is a single static Go
# binary taken from the official image, pinned by digest; Renovate keeps the pin current. The binary
# was scanned with Trivy itself (HIGH and CRITICAL: none) before it was added. The vulnerability
# database is downloaded at scan time into Petri's data volume, so the image never ships a stale one.
FROM aquasec/trivy:0.76.0@sha256:2416d1f74045b57274df1a340eee6b510cacd73c66a6ccb8da092b117eb7d222 AS trivy

# Vulnerability database for air-gapped use. Off by default (the image then downloads and refreshes
# the database at scan time); build with --build-arg BAKE_TRIVY_DB=true to supply the database in
# the image at /opt/trivy-db, and switch the scanner to "offline" under Administration >
# Vulnerability scanning. The database is only as current as the build, so rebuild to refresh it.
#   --build-arg TRIVY_DB_REPOSITORY=registry.example.com/security/trivy-db:2   a registry of your own
#   --secret id=trivy_username,env=USER --secret id=trivy_password,env=PASS     its credentials
FROM trivy AS trivy-db
ARG BAKE_TRIVY_DB=false
ARG TRIVY_DB_REPOSITORY=
RUN --mount=type=secret,id=trivy_username,required=false \
    --mount=type=secret,id=trivy_password,required=false \
    mkdir -p /seed && \
    if [ "$BAKE_TRIVY_DB" = "true" ]; then \
      if [ -f /run/secrets/trivy_username ]; then export TRIVY_USERNAME="$(cat /run/secrets/trivy_username)"; fi; \
      if [ -f /run/secrets/trivy_password ]; then export TRIVY_PASSWORD="$(cat /run/secrets/trivy_password)"; fi; \
      trivy image --download-db-only --quiet --no-progress --cache-dir /seed \
        ${TRIVY_DB_REPOSITORY:+--db-repository "$TRIVY_DB_REPOSITORY"}; \
      test -s /seed/db/trivy.db; \
    fi

# Alpine JDK: Petri runs approved tests in this container (PETRI_TESTS_EXECUTOR=local) and lets
# agents install further Alpine packages into an unprivileged toolchain prefix on /app/data.
FROM eclipse-temurin:25-jdk-alpine
COPY --from=confine /petri-confine /usr/local/bin/petri-confine
COPY --from=trivy /usr/local/bin/trivy /usr/local/bin/trivy
COPY --from=trivy-db /seed/ /opt/trivy-db/

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
    PETRI_TERMINAL_CONFINE_COMMAND=/usr/local/bin/petri-confine \
    HOME=/home/petri \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=20.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/urandom"

COPY --chown=65532:0 target/petri-*.jar /app/app.jar
COPY --chown=65532:0 skills/ /app/skills/

USER petri
VOLUME ["/app/data"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
