# Next implementation steps

Status: the OIDC-account-linking and AES-256-GCM foundations are committed. Encrypted Git credentials, controlled repository mirrors and persisted disposable worktrees are implemented locally and verified; no commit or push was created by the agent for these latest slices.

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

## 4. Build the first controlled agent tool slice — next

1. Define tool descriptors with JSON schemas for list-files, read-file, search, create-patch, show-diff and run-tests.
2. Add workspace confinement, file-size/output limits and content hashes for read-then-patch conflict detection.
3. Model a change proposal and explicit approval state; do not apply patches until approval is recorded.
4. Expose agent-run events through REST plus Server-Sent Events and persist an audit trail.

## 5. Verification discipline for every slice

1. Add focused unit/integration tests first, then run `./mvnw -o test` and `./mvnw -o package`.
2. Run `git diff --check` before handoff.
3. Persist each verified milestone in Hindsight and keep this file current if sequencing changes.
