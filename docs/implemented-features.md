# Implemented features

## Application foundation

- Spring Boot 4.1 application on Java 25.
- Thymeleaf operator console with bundled Tailwind CSS, Alpine.js and Lucide icons.
- Flyway-managed H2 and PostgreSQL schema.
- Health endpoint and Prometheus registry support.
- Local administrator login and optional OpenID Connect login.
- Administrator-created API keys for programmatic clients.
- Audited security-sensitive and agent lifecycle operations.

## Credentials and secrets

- AES-256-GCM encryption for Git and model-provider secrets.
- Automatic owner-only filesystem master key when no environment key is configured.
- Secret values are never rendered back into forms or API status responses.
- Project-scoped HTTPS token or username/password credentials.
- Provider-scoped model credentials and endpoint settings.

## Repository isolation

- Controlled bare mirror per project.
- Clone, fetch and prune without putting credentials in Git command arguments.
- Disposable detached worktrees under a validated workspace root.
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

## Agent runtime

- Persistent runs and ordered user, model, tool-call, tool-result and error turns.
- Explicit model-turn budgets.
- Execution leases that prevent concurrent workers from driving one run.
- Cancellation and resumable interrupted runs.
- Asynchronous start/resume for browser and API clients.
- Run-scoped and worktree-scoped lifecycle events with JSON replay and SSE streaming.
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

File edits are optimistic: a proposal includes the hash observed during the read and becomes `CONFLICT` if the file changed before application. `create_file` proposals for new files become `CONFLICT` if the file exists before application. Test execution is limited to recognized Maven or Gradle wrappers and requires separate approval.

## Current boundaries

- Model output is persisted per completed response; token-by-token browser streaming is not implemented.
- The chat displays plain preformatted text rather than rendered Markdown.
- Model configurations are one profile per provider; reusable named profiles are not implemented.
- Test execution is an approval boundary, not an operating-system sandbox.
- A VS Code client is planned but not yet implemented.
