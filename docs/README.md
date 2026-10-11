# Petri documentation

Petri is a self-hosted Java coding agent. It works on disposable Git worktrees, persists every model and tool turn, and keeps file changes and test execution behind explicit administrator approval.

## Documentation map

- [Getting started](getting-started.md) — run Petri and complete the first coding task.
- [Implemented features](implemented-features.md) — current capabilities and known boundaries.
- [Configuration](configuration.md) — environment variables, storage and authentication.
- [Model providers](model-providers.md) — OpenAI, LiteLLM, Ollama and compatible local servers.
- [Git mirrors and worktrees](git-workspaces.md) — repository setup, branch resolution and lifecycle.
- [Git provider accounts](git-provider-accounts.md) — personal GitHub, GitLab and Forgejo connections and bulk import.
- [Rootless Podman test sandboxes](test-sandboxes.md) — isolated test execution and administrator profiles.
- [Agent workflow](agent-workflow.md) — model loop, tools, approvals, persistence and recovery.
- [Gated multi-agent orchestration](gated-orchestration.md) — planner questions, plan gates, role profiles, review and recovery.
- [HTTP API](http-api.md) — API-key authentication, worktrees, runs, events and tools.
- [Security model](security.md) — trust boundaries and production deployment guidance.
- [Development](development.md) — build, tests, database migrations and project structure.

## Main operator pages

| Page | Purpose |
| --- | --- |
| `/workspace/projects/{projectId}` | Browse/edit files, review Git changes and work with durable chats. |
| `/projects` | Register repositories and open project settings. |
| `/git-accounts` | Connect personal Git provider accounts. |
| `/git-provider-instances` | Configure personal self-hosted GitHub, GitLab or Forgejo endpoints; admins maintain global instances. |
| `/sandbox-profiles` | Administer rootless Podman test profiles. |
| `/model-settings` | Configure model providers and local endpoints. |
| `/runs` | Inspect persisted runs and approvals. |
| `/api-keys` | Create or revoke API-client credentials. |
| `/audit` | Inspect security and lifecycle events. |

Legacy `/chat` links redirect into the corresponding project workspace. Users manage only their own resources, including personal self-hosted Git instances; global providers, models, audit and sandbox profiles remain administrator-only. Programmatic clients use `/api/v1/**` with an owner-scoped Petri API key.
