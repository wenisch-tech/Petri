# Development environment that matches the release image: Alpine JDK 25, git and an unprivileged
# user, so agents, package installs and local test execution behave as in production.
# Node comes from Alpine (musl): the glibc binary the frontend plugin downloads cannot run here, so
# MAVEN_ARGS (see devcontainer.json) skips that download and the build uses this nodejs/npm.
FROM alpine:3.24 AS confine
RUN apk add --no-cache gcc musl-dev
COPY docker/petri-confine.c /src/petri-confine.c
RUN gcc -O2 -static -o /petri-confine /src/petri-confine.c

# The workspace agent that Kubernetes workspaces copy into their pods (built for this machine's
# architecture only; the release image carries both).
FROM golang:1.27-alpine AS workspace-agent
WORKDIR /src
COPY docker/workspace-agent/ ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags "-s -w -X main.version=dev" \
      -o /out/petri-workspace-agent .

FROM eclipse-temurin:25-jdk-alpine
COPY --from=confine /petri-confine /usr/local/bin/petri-confine
COPY --from=workspace-agent /out/petri-workspace-agent /opt/petri/workspace-agent/dev/petri-workspace-agent

COPY scripts/alpine-runtime.sh /tmp/alpine-runtime.sh
RUN PETRI_UID=1000 sh /tmp/alpine-runtime.sh && rm -f /tmp/alpine-runtime.sh \
 && apk add --no-cache openssh-client nodejs npm

USER petri
WORKDIR /workspaces/Petri
