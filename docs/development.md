# Development

## Build

```bash
./mvnw test
./mvnw package
```

The Maven lifecycle installs the pinned Node/npm versions, runs `npm ci`, builds CSS and JavaScript, compiles Java, applies test migrations and executes the tests.

Offline verification works after dependencies are cached:

```bash
./mvnw -o package
git diff --check
```

## Source layout

```text
src/main/java/tech/wenisch/petri/
  agent/       model clients, orchestration, tools, proposals, tests and events
  apikey/      API-client credentials and authentication
  audit/       persisted audit events
  config/      typed application configuration
  git/         credentials, mirror/worktree lifecycle and Git runner
  project/     project registry
  secret/      master-key loading and authenticated encryption
  security/    local/OIDC identities and HTTP security
  web/         HTML and JSON controllers
  workspace/   safe path derivation and active-worktree lookup

src/main/resources/
  db/migration/  ordered Flyway migrations
  templates/     Thymeleaf pages

src/main/frontend/
  petri.css
  petri.js
```

## Database changes

Add a new numbered Flyway migration instead of modifying a migration that may already have run. Keep migrations compatible with H2's PostgreSQL mode and PostgreSQL itself. Hibernate uses `ddl-auto=validate`, so entity/schema mismatches fail startup and integration tests.

## Testing guidance

- Add focused unit tests for validation and codecs.
- Use local `HttpServer` fixtures for provider HTTP contracts.
- Use integration tests for Flyway, persistence, security and rendered forms.
- Build temporary real Git repositories for mirror/worktree behavior.
- Run the complete package build before handoff.

Tests must not depend on live model providers, remote repositories or external credentials.

## Continuous integration

`.github/workflows/ci.yml` orchestrates reusable workflows:

- `_test.yml` — Java 25 `mvn verify` (includes the frontend build) and a JUnit report.
- `_docker.yml` — builds the Alpine JDK image, checks `/login` and `/actuator/health` latency in
  the running container, runs a **Trivy** vulnerability scan (posted as a PR comment), and on `main`
  pushes, Cosign-signs and attests the image.
- `_release.yml` (main only) — versions and packages the Helm chart, generates a CycloneDX SBOM,
  signs the chart, publishes it to `wenisch-tech/helm-charts` and GHCR OCI, and creates the GitHub
  release.

Versions come from conventional commit tags (`mathieudutour/github-tag-action`). Pull requests build
and scan the image but never push it. The chart source is in `charts/petri`.

## Dev container

`.devcontainer/` provides an Alpine JDK 25 environment that mirrors the release image (same
`scripts/alpine-runtime.sh`, unprivileged user, Landlock helper, `PETRI_TESTS_EXECUTOR=local`). Open the repository in
VS Code or any Dev Containers client; `postCreateCommand` builds the application. Run it with
`./mvnw spring-boot:run` and open the forwarded port 8080. Node/npm come from Alpine (musl), so
`MAVEN_ARGS` skips the frontend plugin's glibc Node download; run `npm ci && npm run build` once
inside the container (done by `postCreateCommand`) before Maven builds.

## Running the image with an arbitrary UID

The image runs as UID 65532 by default and also works with any non-root UID (group 0, `g=u`
permissions): `docker run --user 12345 -v petri-data:/app/data ghcr.io/wenisch-tech/petri`. A fresh
volume must be writable by that UID/group.
