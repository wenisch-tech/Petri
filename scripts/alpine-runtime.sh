#!/bin/sh
# Prepares an Alpine JDK image as Petri's runtime/dev environment. Shared by the release Dockerfile
# and the dev container so both behave identically.
#
# The image must also work with an arbitrary non-root UID ("anyuid", e.g. OpenShift or
# `docker run --user 12345`): everything Petri writes is owned by group 0 with group permissions
# equal to the owner's, and HOME points at a writable directory.
set -eu

# git is required: Petri drives it as a subprocess.
apk add --no-cache git bash curl ca-certificates

PETRI_UID="${PETRI_UID:-65532}"
addgroup -g "$PETRI_UID" -S petri
adduser -u "$PETRI_UID" -S -G petri -h /home/petri -s /bin/bash petri
addgroup petri root

mkdir -p /app/data /home/petri
chown -R "$PETRI_UID":0 /app /home/petri
chmod -R g=u /app /home/petri
