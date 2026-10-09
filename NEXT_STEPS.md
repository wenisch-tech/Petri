# Next implementation steps

Status: authentication, encrypted credentials, controlled Git mirrors/worktrees and automatic filesystem master-key management are committed. The complete controlled tool slice, multi-provider model execution loop, lazy-loaded skills, asynchronous run API, Spring Boot chat console and rich model settings are implemented locally and verified; no commit or push was created by the agent for these latest slices.

## 1. Finish and review the current security slices

1. Review the OIDC migration (`V4`), user identity mapping, role preservation and login-page provider links.
2. Review the secret cipher and configure a production-only `PETRI_SECRETS_MASTER_KEY` (a Base64-encoded 32-byte key) before adding any credential record.
3. Run `./mvnw -o package`, inspect `git diff --check`, then create a focused commit after human review.

## 2. Add project Git credentials as an audited domain model — completed

1. [x] Add a project-scoped credential record with authentication kind, optional public username, encrypted secret, actors and timestamps.
2. [x] Encrypt before persistence, expose only non-secret status, and audit creation, replacement and removal.
3. [x] Add an administrator-only project settings page that never reveals or prefills the secret.
4. [x] Test authorization, encryption at rest, audit entries and fail-closed behavior without a master key.

## 3. Implement controlled Git mirrors and disposable worktrees — completed

1. [x] Define a workspace root property and validate every derived path is below it.
2. [x] Add a shell-free Git-process abstraction with a fixed executable, timeout, bounded output and secret/URL redaction.
3. [x] Clone/fetch a bare mirror per project without command-line secrets, using a short-lived owner-only askpass helper.
4. [x] Create detached worktrees from mirrors, persist their lifecycle and constrain cleanup to the UUID-derived run path.
5. [x] Test traversal and symlink rejection, timeout/output handling, askpass cleanup, local clone/fetch and forced disposable-worktree cleanup.

## 4. Build the first controlled agent tool slice — completed locally

1. [x] Define registered JSON-schema descriptors for `list_files`, `read_file`, `search_files`, `create_patch` and `show_diff`.
2. [x] Add active-worktree confinement, traversal/symlink/`.git` rejection, bounded traversal/file/output sizes and SHA-256 read hashes.
3. [x] Persist change proposals with `PROPOSED`, `APPROVED`, `APPLIED`, `REJECTED` and `CONFLICT` states; only approved proposals can atomically replace a file.
4. [x] Add a separately approved `run_tests` operation with a strict Maven/Gradle command policy, timeout and bounded output.
5. [x] Expose agent-run events through REST plus Server-Sent Events and persist an audit trail.

## 5. Add model-driven run orchestration

1. [x] Persist an agent run and its ordered model/tool turns independently from disposable worktree lifecycle records.
2. [x] Add a provider-neutral model client boundary and an OpenAI Responses API implementation with encrypted credentials.
3. [x] Drive the tool loop with explicit budgets, cancellation, execution leases and resumable persisted state.
4. [x] Surface pending approvals, model output, tool results and terminal status in an administrator run view.

## 6. Add lazy-loaded skills — completed locally

1. [x] Discover bounded `SKILL.md` files from an operator-controlled, non-symlink root.
2. [x] Expose only skill names/descriptions through `list_skills` and load full instructions on demand through `load_skill`.
3. [x] Keep skills subordinate to native tool and approval policy and ship an initial Spring Boot change workflow.
4. [x] Test traversal, symlink, metadata and size boundaries.

## 7. Complete the client-facing session API

1. [x] Expose controlled worktree creation and removal for authenticated API clients.
2. [x] Attach run IDs to streamed lifecycle events and provide a run-specific SSE endpoint with replay.
3. [x] Add asynchronous create-and-start/resume ergonomics suitable for web and VS Code clients without weakening approval boundaries.
4. Add API rate limits and integration tests for concurrent runs, reconnects and cancellation.

## 8. Build the Spring Boot operator console before the editor client

1. [x] Add a chat-style console for choosing projects/worktrees, creating tasks and following persisted model/tool turns.
2. [x] Launch and resume runs in the background while the browser refreshes status without holding the request open.
3. [x] Render patch and test proposals in the conversation with the existing separate approve/apply and approve/execute boundaries.
4. [x] Add an administrator model-settings page for encrypted credentials, model ID, output budget, reasoning effort and text verbosity.
5. [x] Snapshot generation settings per run and permit explicit per-task overrides in the chat and run API.
6. [x] Add OpenAI, LiteLLM, Ollama and generic OpenAI-compatible endpoints with provider-specific base URLs, optional local credentials and provider selection in chat.
7. [x] Reconstruct persisted conversation/tool history for stateless Chat Completions servers while retaining stateful Responses continuations for OpenAI and LiteLLM.
8. Add token streaming, Markdown rendering and optional reusable named model profiles after the persisted response-stream design is defined.

## 9. Build the first VS Code extension

1. Add a TypeScript extension package with secure endpoint/API-key configuration and workspace selection.
2. Implement a chat view that creates runs, consumes SSE progress and renders terminal state.
3. Show patch/test approval requests and open diffs through VS Code APIs before administrator-authorized application.
4. Send active workspace, file and optional selection context without uploading unrelated files.

## 10. Verification discipline for every slice

1. Add focused unit/integration tests first, then run `./mvnw -o test` and `./mvnw -o package`.
2. Run `git diff --check` before handoff.
3. Persist each verified milestone in Hindsight and keep this file current if sequencing changes.
