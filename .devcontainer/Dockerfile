# Development environment that matches the release image: Alpine JDK 25, git and an unprivileged
# user, so agents, package installs and local test execution behave as in production.
# Node comes from Alpine (musl): the glibc binary the frontend plugin downloads cannot run here, so
# MAVEN_ARGS (see devcontainer.json) skips that download and the build uses this nodejs/npm.
FROM eclipse-temurin:25-jdk-alpine

COPY scripts/alpine-runtime.sh /tmp/alpine-runtime.sh
RUN PETRI_UID=1000 sh /tmp/alpine-runtime.sh && rm -f /tmp/alpine-runtime.sh \
 && apk add --no-cache openssh-client nodejs npm

USER petri
WORKDIR /workspaces/Petri
