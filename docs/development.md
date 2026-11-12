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
  workspace/   workspace host seam (layout, files, processes, git, archives), safe path
               derivation and active-worktree lookup

src/main/resources/
  db/migration/  ordered Flyway migrations
  templates/     Thymeleaf pages

src/main/frontend/
  petri.css
  petri.js

docker/workspace-agent/
  Go module of the agent that runs inside Kubernetes workspace pods
```

## Workspace hosts

A project's workspace (bare mirror, worktrees and the processes working on them) lives on a
*workspace host*. Code that acts on a worktree gets the host with
`WorktreeLocator.resolveActiveWorktree(id)` and uses its parts instead of touching the filesystem
directly:

| Part | Purpose |
| --- | --- |
| `layout()` | directory layout, `stat`, temporary directories |
| `files()` | guarded file operations (list, read, search, conditional write, move, delete) |
| `processes()` | bounded process execution |
| `git()` | Git execution, including credential delivery |
| `archives()` | moving a tree between the host and Petri's local filesystem |

Paths obtained from a host are *host paths*. `WorkspaceHostBoundaryTest` fails when code outside the
local implementations imports `java.nio.file.Files`, so a path can later live in a Kubernetes pod
without further changes.

## Workspace agent (Go)

`docker/workspace-agent` is a dependency-free Go module (`petri-workspace-agent`) that Petri copies
into a Kubernetes workspace pod. It serves the host operations above over an authenticated HTTP API
below one workspace root and applies the same path guards as `WorkspaceFileService`.

```bash
cd docker/workspace-agent
gofmt -l . && go vet ./... && go test -race ./...
```

The race detector needs cgo. Without a local Go toolchain run the same commands in
`golang:1.27-alpine` (with `gcc musl-dev` installed). `scripts/build-workspace-agent.sh` builds the
binaries for local development into `./data/workspace-agent/<arch>/`; the release image carries them
in `/opt/petri/workspace-agent/<arch>/`. CI runs the Go checks in a separate job of `_test.yml`.

### Workspace toolchain bundle

`docker/workspace-toolchain/build.sh` builds the bundle of command line tools (git, curl, python,
go, node, npm) that Petri copies into pods. It runs in an Alpine container: it installs the packages
for each architecture into a staging root, rewrites every ELF file's interpreter and library path to
the fixed prefix `/petri/workspace/toolchain`, rewrites script shebangs, writes wrappers and packs a
`toolchain.tar.gz`. `scripts/build-workspace-toolchain.sh` runs it for this machine into
`./data/workspace-toolchain/<arch>/`; `Dockerfile.tools` runs it for both architectures on top of the regular
image and is published as the `-tools` image. The prefix is part of the bundle: the agent installs it at `<root>/toolchain` and
refuses a bundle built for another path.

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

- `_test.yml` — Java 25 `mvn verify` (includes the frontend build) and a JUnit report, plus a Go job
  (`gofmt`, `go vet`, `go test -race`) for the workspace agent.
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

The dev container is set up to try the newer features without extra configuration:

- **Automations** poll every 30 seconds (`PETRI_AUTOMATION_POLL_INTERVAL_MS`) after a 5 second
  initial delay, so a pull request is picked up quickly. To test the code review, connect a GitHub,
  GitLab or Forgejo account at `/git-accounts` (the token needs permission to comment on pull
  requests), import a repository, configure a model provider at `/model-settings`, then enable
  *Code review* at `/projects/{id}/automations`. Open a pull request, or **Review open pull
  requests now** for existing ones. Skills are read from `./skills`.
- **Proxy and TLS** settings are taken from the host environment when the container is created:
  `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY`, `IGNORE_TLS`. Proxy variables are forwarded to terminal
  commands, tests and package installs; add more under *Environment variables* of a sandbox
  profile at `/sandbox-profiles`.
- **OIDC** can be tried by exporting `OIDC_ENABLED=true` and `OIDC_PROVIDERS_0_*` /
  `OIDC_PROVIDERS_1_*` on the host before opening the container; `OIDC_INSECURE_SKIP_TLS_VERIFY` and
  `OIDC_CREATEUSERS` are passed through as well. Register `http://localhost:8080/login/oauth2/code/<registration-id>`
  as the redirect URI. Rebuild the container after changing host variables.

## Running the image with an arbitrary UID

The image runs as UID 65532 by default and also works with any non-root UID (group 0, `g=u`
permissions): `docker run --user 12345 -v petri-data:/app/data ghcr.io/wenisch-tech/petri`. A fresh
volume must be writable by that UID/group.
