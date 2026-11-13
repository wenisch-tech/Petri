# syntax=docker/dockerfile:1.29@sha256:572cebbc2b15e6f84c077742952674889cb63cd5e9b80abdffcbe587b6f19183

# petri-confine: a tiny static Landlock helper that confines terminal and test commands to the
# worktree for any UID, without root or setuid.
FROM alpine:3.24 AS confine
ARG APK_MIRROR
RUN if [ -n "$APK_MIRROR" ]; then sed -i "s#https\?://[^/]*/alpine#${APK_MIRROR%/}#" /etc/apk/repositories; fi \
 && apk add --no-cache gcc musl-dev
COPY docker/petri-confine.c /src/petri-confine.c
RUN gcc -O2 -static -o /petri-confine /src/petri-confine.c

# Trivy: the vulnerability scanner behind the vulnerability-scan skill. It is a single static Go
# binary taken from the official image, pinned by digest; Renovate keeps the pin current. The binary
# was scanned with Trivy itself (HIGH and CRITICAL: none) before it was added. The vulnerability
# database is downloaded at scan time into Petri's data volume, so the image never ships a stale one.
FROM aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb AS trivy

# Trivy v0.76.1 still embeds oras.land/oras-go/v2 v2.6.2 (open advisories, fixed in v2.6.4), golang.org/x/net v0.60.0 (fixed in v0.62.0) and go-jose v4.1.5 (fixed in v4.1.7).
# Until upstream bumps them, the shipped binary is built from the release tag with them raised.
FROM golang:1.27-alpine AS trivy-build
ARG TRIVY_VERSION=0.76.1
ARG ORAS_GO_VERSION=2.6.4
ARG X_NET_VERSION=0.62.0
ARG GO_JOSE_VERSION=4.1.7
ARG APK_MIRROR
RUN if [ -n "$APK_MIRROR" ]; then sed -i "s#https\?://[^/]*/alpine#${APK_MIRROR%/}#" /etc/apk/repositories; fi \
 && apk add --no-cache git
RUN git clone --depth 1 --branch "v${TRIVY_VERSION}" https://github.com/aquasecurity/trivy.git /src
WORKDIR /src
RUN go get "oras.land/oras-go/v2@v${ORAS_GO_VERSION}" "golang.org/x/net@v${X_NET_VERSION}" "github.com/go-jose/go-jose/v4@v${GO_JOSE_VERSION}" \
 && CGO_ENABLED=0 go build -trimpath \
      -ldflags "-s -w -X github.com/aquasecurity/trivy/pkg/version/app.ver=${TRIVY_VERSION}" \
      -o /trivy ./cmd/trivy

# Workspace agent: a stdlib-only Go binary that runs inside Kubernetes workspace pods and serves the
# file, process and archive operations of a project whose workspace lives on a cluster. Petri copies
# it into the pod at start, so both Linux architectures are built and one image serves any node.
FROM golang:1.27-alpine AS workspace-agent
ARG BUILD_VERSION=dev
WORKDIR /src
COPY docker/workspace-agent/ ./
RUN go vet ./... \
 && for arch in amd64 arm64; do \
      CGO_ENABLED=0 GOOS=linux GOARCH=$arch go build -trimpath \
        -ldflags "-s -w -X main.version=${BUILD_VERSION}" \
        -o /out/$arch/petri-workspace-agent . ; \
    done

# Vulnerability database, supplied in the image at /opt/trivy-db so that scans work without any
# registry access (air-gapped installations switch the scanner to "offline" under Administration >
# Vulnerability scanning). It is only as current as the build; an online instance refreshes it at
# scan time. The image grows by the database (about 1.6 GB uncompressed); build with
# --build-arg BAKE_TRIVY_DB=false for a slim image that downloads it on first use instead.
#   --build-arg TRIVY_DB_REPOSITORY=registry.example.com/security/trivy-db:2   a registry of your own
#   --secret id=trivy_username,env=USER --secret id=trivy_password,env=PASS     its credentials
FROM trivy AS trivy-db
ARG BAKE_TRIVY_DB=true
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
COPY --from=trivy-build /trivy /usr/local/bin/trivy
COPY --from=trivy-db /seed/ /opt/trivy-db/
COPY --from=workspace-agent /out/ /opt/petri/workspace-agent/

ARG APK_MIRROR
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
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=20.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/urandom"

COPY --chown=65532:0 target/petri-*.jar /app/app.jar
COPY --chown=65532:0 skills/ /app/skills/

USER petri
VOLUME ["/app/data"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
