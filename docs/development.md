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
