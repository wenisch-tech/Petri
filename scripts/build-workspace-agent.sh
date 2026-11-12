#!/bin/sh
# Builds the Kubernetes workspace agent for local development and writes it where Petri looks for it
# (PETRI_KUBERNETES_AGENT_DIR, default ./data/workspace-agent). Needs Go, or Docker as a fallback.
#
#   scripts/build-workspace-agent.sh [amd64 arm64]
#
# Layout: <dir>/<arch>/petri-workspace-agent, the same as /opt/petri/workspace-agent in the image.
set -eu

here="$(cd "$(dirname "$0")/.." && pwd)"
out="${PETRI_KUBERNETES_AGENT_DIR:-$here/data/workspace-agent}"
version="${BUILD_VERSION:-dev}"
[ "$#" -gt 0 ] || set -- amd64 arm64

for arch in "$@"; do
  mkdir -p "$out/$arch"
  if command -v go >/dev/null 2>&1; then
    (cd "$here/docker/workspace-agent" && CGO_ENABLED=0 GOOS=linux GOARCH="$arch" \
      go build -trimpath -ldflags "-s -w -X main.version=$version" -o "$out/$arch/petri-workspace-agent" .)
  else
    docker run --rm -v "$here/docker/workspace-agent:/src:ro" -v "$out/$arch:/out" -w /src \
      -e CGO_ENABLED=0 -e GOOS=linux -e GOARCH="$arch" golang:1.27-alpine \
      go build -trimpath -ldflags "-s -w -X main.version=$version" -o /out/petri-workspace-agent .
  fi
  echo "built $out/$arch/petri-workspace-agent"
done
