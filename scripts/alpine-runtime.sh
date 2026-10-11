#!/bin/sh
# Prepares an Alpine JDK image as Petri's runtime/dev environment. Shared by the release Dockerfile
# and the dev container so both behave identically.
set -eu

# git is required (Petri drives it as a subprocess); the rest are common build-tool prerequisites.
apk add --no-cache git bash curl ca-certificates

PETRI_UID="${PETRI_UID:-65532}"
addgroup -g "$PETRI_UID" -S petri
adduser -u "$PETRI_UID" -S -G petri -h /home/petri -s /bin/bash petri
mkdir -p /app/data /home/petri
chown -R petri:petri /app /home/petri
