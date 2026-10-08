# Petri

Petri is a self-hosted, Java-based coding agent for controlled analysis, modification and verification of software projects.

## Development

Requirements: JDK 25 and Maven 3.9+.

```bash
./mvnw test
./mvnw spring-boot:run
```

Open <http://localhost:8080> and sign in with `admin@petri.local` / `admin` for local evaluation. Set `PETRI_ADMIN_EMAIL` and `PETRI_ADMIN_PASSWORD` before exposing the service beyond localhost.

The standalone profile stores its H2 database below `data/`. PostgreSQL can be selected through the standard `SPRING_DATASOURCE_*` environment variables.

## Secret encryption

Repository credentials and future model-provider credentials are encrypted with AES-256-GCM before persistence. If no key is configured, Petri creates a random 256-bit key once at `./data/petri-master.key`, restricts the file to its owner, and reuses it after restarts. Override the location with `PETRI_SECRETS_MASTER_KEY_FILE`, or supply a Base64-encoded 32-byte key directly with `PETRI_SECRETS_MASTER_KEY`; the explicit key takes precedence and no key file is created.

```bash
export PETRI_SECRETS_MASTER_KEY="$(openssl rand -base64 32)"
# or keep automatic generation outside the application directory:
export PETRI_SECRETS_MASTER_KEY_FILE=/var/lib/petri/secrets/master.key
```

Back up whichever key source is used. Petri refuses malformed or symlinked key files instead of replacing them, because losing or changing the key makes existing encrypted records unrecoverable.

An instance administrator can configure or remove an HTTPS token or username/password credential from a project's settings page. Petri only displays whether a credential exists; the stored secret is never rendered back into the UI.

## Git workspace

Petri stores bare project mirrors and disposable run worktrees below `PETRI_WORKSPACE_ROOT` (default `./data/workspaces`). Git is executed directly without a shell, with interactive prompts disabled, a configurable timeout, bounded and redacted output, and global/system Git configuration disabled. HTTPS credentials are supplied through a temporary owner-only askpass helper and never through command arguments.

```bash
export PETRI_WORKSPACE_ROOT=/var/lib/petri/workspaces
export PETRI_GIT_COMMAND_TIMEOUT=2m
export PETRI_MAX_COMMAND_OUTPUT_BYTES=1000000
```

## Agent tools

The first native tools are `list_files`, `read_file`, `search_files`, `create_patch`, `show_diff` and `run_tests`. Every call is scoped to an active persisted worktree; relative paths, real paths and symlinks are checked before access. File reads return a SHA-256 hash, and a patch proposal must include that exact hash. Proposals are persisted for review and cannot modify a file until an instance administrator approves them. Application uses an atomic replacement and records `CONFLICT` instead of overwriting a file changed after the original read.

`run_tests` also creates a proposal instead of launching a process. Once separately approved, Petri permits only one of these fixed commands, selected from safe build files and non-symlinked executable wrappers:

- `./mvnw --batch-mode --no-transfer-progress test`
- `./gradlew --no-daemon test`

The test process receives a reduced environment, has its process tree terminated on timeout, and stores bounded combined output. Configure its limits with `PETRI_TEST_COMMAND_TIMEOUT` and `PETRI_MAX_TEST_OUTPUT_BYTES`. Approval is a safety boundary, not an operating-system sandbox: deploy Petri under an isolated, least-privileged account or container before running untrusted repositories.

Tool file/output sizes and search result counts are bounded through `PETRI_MAX_TOOL_FILE_BYTES`, `PETRI_MAX_TOOL_OUTPUT_BYTES` and `PETRI_MAX_SEARCH_RESULTS`.

API clients can discover and invoke tools below `/api/v1/agent`. Tool lifecycle events are stored, mirrored into the audit log, available as recent JSON events, and streamed live as Server-Sent Events from `/api/v1/agent/worktrees/{worktreeId}/events/stream`. API clients may request privileged work but cannot approve it. Approval, rejection and execution endpoints live below `/operations` and require an authenticated instance administrator plus CSRF protection.

## Model providers and agent runs

Model access is isolated behind a provider-neutral client boundary. The first implementation uses OpenAI's Responses API with strict function schemas, one tool call at a time, bounded responses and no redirects. An instance administrator stores an OpenAI API key plus an explicit model name through `/operations/model-credentials/OPENAI`; the API key is encrypted before persistence and is never returned by status responses.

API clients can create, inspect, list and cancel persistent runs below `/api/v1/agent/worktrees/{worktreeId}/runs`. Runs and their ordered turns are separate from disposable worktree lifecycle records. The execution loop is the next slice; newly created runs therefore remain `PENDING` unless cancelled.

```bash
export PETRI_OPENAI_BASE_URL=https://api.openai.com/v1
export PETRI_MODEL_REQUEST_TIMEOUT=2m
export PETRI_MAX_MODEL_RESPONSE_BYTES=2000000
export PETRI_MODEL_MAX_OUTPUT_TOKENS=4096
```

## OIDC

Local login remains available by default. To enable an OpenID Connect provider, configure a standard Spring Security client registration with `openid` and `email` scopes. Petri requires the provider's `email_verified` claim before it creates or links an account. A verified email matching an existing local account retains that account's Petri role.

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: petri
            client-secret: change-me
            scope: openid,profile,email
        provider:
          keycloak:
            issuer-uri: https://sso.example.test/realms/platform
```

## Status

Phase 1 is in progress. The current slices provide the reproducible backend/frontend build, Flyway-managed persistence, local and optional OIDC authentication, project registry, audit events, administrator-managed API keys, encrypted Git/model credentials, controlled repository mirrors, disposable worktrees, approval-gated file/test tools, REST invocation, persisted SSE lifecycle events and the persistent provider-neutral model/run foundation.

## License

Petri is licensed under the GNU Affero General Public License v3.0.
