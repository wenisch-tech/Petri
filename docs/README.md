# Petri documentation

Petri is a self-hosted Java coding agent. It works on disposable Git worktrees, persists every model and tool turn, and keeps file changes and test execution behind explicit administrator approval.

## Documentation map

- [Getting started](getting-started.md) — run Petri and complete the first coding task.
- [Implemented features](implemented-features.md) — current capabilities and known boundaries.
- [Configuration](configuration.md) — environment variables, storage and authentication.
- [Model providers](model-providers.md) — OpenAI, LiteLLM, Ollama and compatible local servers.
- [Git mirrors and worktrees](git-workspaces.md) — repository setup, branch resolution and lifecycle.
- [Agent workflow](agent-workflow.md) — model loop, tools, approvals, persistence and recovery.
- [HTTP API](http-api.md) — API-key authentication, worktrees, runs, events and tools.
- [Security model](security.md) — trust boundaries and production deployment guidance.
- [Development](development.md) — build, tests, database migrations and project structure.

## Main operator pages

| Page | Purpose |
| --- | --- |
| `/workspace/projects/{projectId}` | Browse/edit files, review Git changes and work with durable chats. |
| `/projects` | Register repositories and open project settings. |
| `/model-settings` | Configure model providers and local endpoints. |
| `/runs` | Inspect persisted runs and approvals. |
| `/api-keys` | Create or revoke API-client credentials. |
| `/audit` | Inspect security and lifecycle events. |

Legacy `/chat` links redirect into the corresponding project workspace. The browser console is intended for instance administrators. Programmatic clients use `/api/v1/**` with a Petri API key.
