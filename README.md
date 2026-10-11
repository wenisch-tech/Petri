# Petri

Petri is a self-hosted, Java-based coding agent for controlled analysis, modification and verification of software projects.

See the [documentation index](docs/README.md) for setup, configuration, model providers, Git workspaces, agent behavior, API usage and the security model.

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

Users can configure project-specific Simple Git credentials or connect personal GitHub, GitLab and Forgejo accounts and import several repositories from a live catalog. Petri never renders stored secrets back into the UI. See [Git provider accounts](docs/git-provider-accounts.md).

## Git workspace

Petri stores bare project mirrors and disposable run worktrees below `PETRI_WORKSPACE_ROOT` (default `./data/workspaces`). Git is executed directly without a shell, with interactive prompts disabled, a configurable timeout, bounded and redacted output, and global/system Git configuration disabled. HTTPS credentials are supplied through a temporary owner-only askpass helper and never through command arguments.

After a project owner has synchronized a project mirror, that owner's API clients can create, list and remove disposable worktrees through `/api/v1/projects/{projectId}/worktrees`. Removal verifies ownership, the project association and the UUID-derived safe path before invoking Git.

```bash
export PETRI_WORKSPACE_ROOT=/var/lib/petri/workspaces
export PETRI_GIT_COMMAND_TIMEOUT=2m
export PETRI_MAX_COMMAND_OUTPUT_BYTES=1000000
```

## Agent tools

The first native tools are `list_files`, `read_file`, `search_files`, `create_patch`, `show_diff` and `run_tests`. Every call is scoped to an active persisted worktree; relative paths, real paths and symlinks are checked before access. File reads return a SHA-256 hash, and a patch proposal must include that exact hash. Proposals are persisted for review and cannot modify a file until the project owner approves them. Application uses an atomic replacement and records `CONFLICT` instead of overwriting a file changed after the original read.

`run_tests` also creates a proposal instead of launching a process. Once separately approved, Petri permits only one of these fixed commands, selected from safe build files and non-symlinked executable wrappers:

- `./mvnw --batch-mode --no-transfer-progress test`
- `./gradlew --no-daemon test`

Approved tests run exclusively in rootless Podman with an immutable root filesystem, dropped capabilities, resource limits and project-isolated caches. There is no host fallback. See [test sandboxes](docs/test-sandboxes.md).

Tool file/output sizes and search result counts are bounded through `PETRI_MAX_TOOL_FILE_BYTES`, `PETRI_MAX_TOOL_OUTPUT_BYTES` and `PETRI_MAX_SEARCH_RESULTS`.

## Skills

Petri discovers reusable workflows from `<skills-root>/<skill-name>/SKILL.md`. The model first calls `list_skills`, which returns only names and short descriptions, and loads the full instructions with `load_skill` only when a workflow is relevant. Skills provide operating guidance but cannot add capabilities or bypass tool and approval policy.

Skill directories use lowercase names with digits and hyphens. Each UTF-8 `SKILL.md` starts with bounded YAML-style front matter containing a matching `name` and a one-line `description`. Symlinked roots, directories and files are rejected. The repository includes `spring-boot-change` as the first workflow.

```bash
export PETRI_SKILLS_ROOT=/etc/petri/skills
export PETRI_MAX_SKILL_BYTES=200000
```

API clients can discover and invoke tools below `/api/v1/agent`. Tool lifecycle events are stored, mirrored into the audit log, available as recent JSON events, and streamed live as Server-Sent Events from `/api/v1/agent/worktrees/{worktreeId}/events/stream`. API clients may request privileged work but cannot approve it. Approval, rejection and execution endpoints live below `/operations` and require the authenticated project owner plus CSRF protection.

## Model providers and agent runs

Model access is isolated behind a provider-neutral client boundary. An instance administrator configures one or more providers at `/model-settings`; each has its own base URL, model ID, maximum output tokens, optional reasoning/verbosity controls and optional encrypted API key. Stored keys are never rendered after submission. The REST operations under `/operations/model-credentials/{provider}` support the same settings for automation.

| Provider | Default base URL | Protocol | API key |
| --- | --- | --- | --- |
| `OPENAI` | `https://api.openai.com/v1` | Responses API | required |
| `LITELLM` | `http://localhost:4000/v1` | Responses API | optional |
| `OLLAMA` | `http://localhost:11434/v1` | Chat Completions | optional |
| `OPENAI_COMPATIBLE` | `http://localhost:8000/v1` | Chat Completions | optional |

LiteLLM can route any configured model alias through its Responses endpoint. Ollama and generic OpenAI-compatible endpoints use Chat Completions because this is the broadest local-server contract; Petri reconstructs the full conversation and tool-call history from persisted turns for every request, so no provider-side conversation state is required. Base URLs may point at another container or LAN host. Use plain HTTP only on a trusted local network, especially when an API key is configured.

New runs snapshot their provider, model identity and generation settings so later default changes do not alter those values on an existing or resumable run. API clients and the web chat can override the configured model and generation defaults for an individual run. Reasoning effort and verbosity are optional because support differs by model.

API clients can create, inspect, list, execute and cancel persistent runs below `/api/v1/agent/worktrees/{worktreeId}/runs`. A new run starts as `PENDING`; `POST /api/v1/agent/worktrees/{worktreeId}/runs/{runId}/execute` drives the synchronous model/tool loop until it completes, fails, is cancelled or exhausts its model-turn budget. `POST .../runs/start` combines creation with asynchronous execution, and `POST .../runs/{runId}/resume` queues a persisted run without holding the client connection. Runs and their ordered turns are separate from disposable worktree lifecycle records.

Run-scoped tool lifecycle events are available as replayable JSON and Server-Sent Events at `/api/v1/agent/worktrees/{worktreeId}/runs/{runId}/events` and `/events/stream`. Worktree-wide event endpoints remain available for broader monitoring.

Each model response, tool call and tool result is persisted before the next model request. Continuations use the provider response ID and original tool-call ID. An execution lease prevents concurrent workers from driving the same run and allows an interrupted `RUNNING` run to be resumed after the lease expires. Cancellation is checked between external calls and tools. File changes and tests remain proposals: model execution never bypasses owner approval gates.

Project owners work from the workspace and embedded chat at `/workspace/projects/{projectId}`; legacy `/chat` URLs redirect there. The workspace selects a configured provider, project worktree and durable chat, starts the model loop in the background, streams progress, and embeds patch and test approval actions in the conversation. Per-message controls can override model ID, output budget, reasoning effort and verbosity. The `/runs` pages remain the detailed operational view. Approved changes and tests still require a separate apply or execute action unless the chat owner explicitly enables the corresponding automation.

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

The current implementation provides a reproducible backend/frontend build, Flyway-managed persistence, local and optional OIDC authentication, owner-scoped projects and API keys, connected Git-provider accounts, encrypted Git/model credentials, controlled mirrors and worktrees, approval-gated file tools, Podman-isolated tests, lazy-loaded skills, persistent SSE lifecycle events, provider-neutral model/tool orchestration and the browser workspace with embedded durable chat.

## License

Petri is licensed under the GNU Affero General Public License v3.0.
