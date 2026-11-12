#!/bin/sh
# Builds the workspace toolchain bundle for local development and writes it where Petri looks for it
# (PETRI_KUBERNETES_TOOLCHAIN_DIR, default ./data/workspace-toolchain). Needs Docker and internet access.
#
#   scripts/build-workspace-toolchain.sh [amd64 arm64]     default: this machine's architecture
#
# Layout: <dir>/<arch>/toolchain.tar.gz, the same as /opt/petri/workspace-toolchain in the image.
set -eu

here="$(cd "$(dirname "$0")/.." && pwd)"
out="${PETRI_KUBERNETES_TOOLCHAIN_DIR:-$here/data/workspace-toolchain}"
if [ "$#" -eq 0 ]; then
  case "$(uname -m)" in aarch64|arm64) set -- arm64 ;; *) set -- amd64 ;; esac
fi

arches=""
for arch in "$@"; do
  case "$arch" in
    amd64) arches="$arches x86_64" ;;
    arm64) arches="$arches aarch64" ;;
    *) echo "unknown architecture $arch (use amd64 or arm64)" >&2; exit 1 ;;
  esac
done

mkdir -p "$out"
docker run --rm -v "$here/docker/workspace-toolchain:/build:ro" -v "$out:/out" \
  -v petri-workspace-toolchain-apk:/var/cache/workspace-toolchain-apk \
  -e OWNER="$(id -u):$(id -g)" -e ARCHES="${arches# }" -e PREFIX="${PETRI_TOOLCHAIN_PREFIX:-/petri/workspace/toolchain}" \
  ${WORKSPACE_TOOLCHAIN_PACKAGES:+-e PACKAGES="$WORKSPACE_TOOLCHAIN_PACKAGES"} \
  alpine:3.24 sh /build/build.sh
