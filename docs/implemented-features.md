# Implemented features

## Application foundation

- Spring Boot 4.1 application on Java 25.
- Thymeleaf operator console with bundled Tailwind CSS, Alpine.js and Lucide icons.
- Flyway-managed H2 and PostgreSQL schema.
- Health endpoint and Prometheus registry support.
- Local administrator login and optional OpenID Connect login.
- Owner-scoped self-service API keys for programmatic clients.
- Audited security-sensitive and agent lifecycle operations.

## Credentials and secrets

- AES-256-GCM encryption for Git and model-provider secrets.
- Automatic owner-only filesystem master key when no environment key is configured.
- Secret values are never rendered back into forms or API status responses.
- Project-scoped HTTPS token or username/password credentials.
- Personal GitHub, GitLab and Forgejo PAT/OAuth connections with encrypted tokens.
- Live provider repository catalogs, bulk import and recoverable mirror jobs.
- Provider-scoped model credentials and endpoint settings.

## Repository isolation

- Controlled bare repository per project with separate local and cached `origin/*` refs.
- Clone, fetch and prune without putting credentials in Git command arguments.
- Dedicated branch-bound worktrees plus compatible detached legacy/API worktrees.
- Branch creation/open/delete, staged and unstaged changes, commit, fast-forward pull, rebase/continue/abort, normal push and explicit force-with-lease.
- Traversal, symlink and `.git` access protections in agent file tools.
- Bounded Git time, output, file reads, searches and tool results.
- Default-worktree creation falls back to the mirror's actual `HEAD` branch when the configured default does not exist.

## Model providers

- OpenAI Responses API.
- LiteLLM Responses API.
- Ollama OpenAI-compatible Chat Completions API.
- Generic OpenAI-compatible Chat Completions endpoint.
- Independent base URL, model, output budget and optional key per provider.
- Optional reasoning effort and text verbosity controls.
- Per-run model setting snapshots and per-task overrides.
- Full persisted conversation reconstruction for stateless Chat Completions servers.
- Configurable context-window snapshot per provider and message.

## Workspace UI

- Full-height project workspace with VS Code-style lazy file explorer, tabs and Monaco Editor.
- Local Monaco language workers and native responsive side-by-side/inline Git and proposal diffs.
- Persistent browser drafts, cursor/view state, optimistic SHA saves and conflict-safe reload behavior.
- Files/Changes navigator with Git decorations and staged, unstaged, untracked and conflict groups.
- Embedded durable streaming chat, persistent project model selection and inline/pinned approvals.
- Git bar with branch before worktree selection and non-reloading Git status/actions.

## Agent runtime

- Persistent runs and ordered user, model, tool-call, tool-result and error turns.
- Explicit model-turn budgets.
- Execution leases that prevent concurrent workers from driving one run.
- Cancellation and resumable interrupted runs.
- Asynchronous start/resume for browser and API clients.
- Run-scoped and worktree-scoped lifecycle events with JSON replay and SSE streaming.
- Durable worktree-bound chats: each message is a separately persisted run in an ordered queue.
- One active run per chat, recovery of pending/expired work after restart, explicit queue resume and approval gates.
- Token-by-token model text streaming with persisted partial output for reload/reconnect recovery.
- Cumulative context summaries when a conversation exceeds its configured context budget; source history remains visible.
- Gated Orchestrator → Planner → Implementer → Reviewer workflows with strict typed artifacts and fail-closed model gates.
- Structured planner questions, revision-bound plan approval, reviewer findings and user guidance at safe boundaries.
- Per-user/project model and generation profiles for all four isolated roles, with immutable task snapshots.
- Exclusive worktree reservations, dirty-state Git baselines, workflow-only diffs/hashes and ordered follow-up workflow queues.
- Role-specific server-side tool allowlists, workflow-scoped automatic patch/test consent and idempotent recovery.
- Lazy-loaded operator-controlled `SKILL.md` workflows.

## Native tools

- `list_files`
- `read_file`
- `search_files`
- `create_patch`
- `create_file`
- `show_diff`
- `run_tests`
- `list_skills`
- `load_skill`

File edits are optimistic: a proposal includes the hash observed during the read and becomes `CONFLICT` if the file changed before application. `create_file` proposals for new files become `CONFLICT` if the file exists before application. Test execution is limited to recognized Maven or Gradle wrappers, requires separate approval and runs only in an administrator-managed rootless Podman profile.

## Current boundaries

- Model configurations are one profile per provider; reusable named profiles are not implemented.
- GitHub Apps, deploy keys, account-level SSH keys and provider webhooks are not implemented.
- Git merge, cherry-pick, tags, interactive rebase, commit signing, submodules, LFS, staging individual hunks and reverting files are not implemented.
