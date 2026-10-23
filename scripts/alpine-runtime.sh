#!/bin/sh
# Prepares an Alpine JDK image as Petri's runtime/dev environment. Shared by the release Dockerfile
# and the dev container so both behave identically.
#
# The image must also work with an arbitrary non-root UID ("anyuid", e.g. OpenShift or
# `docker run --user 12345`): everything Petri writes is owned by group 0 with group permissions
# equal to the owner's, and HOME points at a writable directory.
set -eu

# A mirror of the Alpine repositories (APK_MIRROR, e.g. https://nexus.example.com/alpine, without
# credentials) replaces dl-cdn.alpinelinux.org for the whole build, so it works without internet.
ALPINE_BASE="${APK_MIRROR:-https://dl-cdn.alpinelinux.org/alpine}"
ALPINE_BASE="${ALPINE_BASE%/}"
if [ -n "${APK_MIRROR:-}" ]; then
  sed -i "s#https\?://[^/]*/alpine#${ALPINE_BASE}#" /etc/apk/repositories
fi

# git is required: Petri drives it as a subprocess.
apk add --no-cache git bash curl ca-certificates

# Security floors for libraries that ship in the JDK base image. `--upgrade` raises them to at
# least these versions (and to anything newer the repository offers) in this layer.
apk add --no-cache --upgrade 'libpng>=1.6.59-r0' 'zlib>=1.3.2-r1'

# libexpat: security floor 2.9.0-r0 (several CVEs are fixed in it). Alpine v3.24 still ships
# 2.8.5-r0 in its own repositories, so this one library comes from edge/main until v3.24 has caught
# up. libexpat depends on musl only and keeps its soname (libexpat.so.1), so it is a drop-in
# replacement for the packages that link against it (git, curl). The constraint makes the
# step a no-op once the base image already carries a fixed version.
apk add --no-cache --repository "${ALPINE_BASE}/edge/main" 'libexpat>=2.9.0-r0'

# curl/libcurl: security floor 8.23.0-r0. Alpine v3.24 still ships 8.22.0-r0, so both come from
# edge/main until v3.24 has caught up; the constraint makes this a no-op once the base is fixed.
apk add --no-cache --upgrade --repository "${ALPINE_BASE}/edge/main" 'curl>=8.23.0-r0' 'libcurl>=8.23.0-r0'

PETRI_UID="${PETRI_UID:-65532}"
addgroup -g "$PETRI_UID" -S petri
adduser -u "$PETRI_UID" -S -G petri -h /home/petri -s /bin/bash petri
addgroup petri root

mkdir -p /app/data /home/petri
chown -R "$PETRI_UID":0 /app /home/petri
chmod -R g=u /app /home/petri
