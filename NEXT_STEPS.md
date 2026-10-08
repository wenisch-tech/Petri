# Next implementation steps

Status: the OIDC-account-linking slice and the AES-256-GCM secret-encryption foundation are implemented locally and verified. The working tree intentionally contains these uncommitted changes; no commit or push was created by the agent.

## 1. Finish and review the current security slices

1. Review the OIDC migration (`V4`), user identity mapping, role preservation and login-page provider links.
2. Review the secret cipher and configure a production-only `PETRI_SECRETS_MASTER_KEY` (a Base64-encoded 32-byte key) before adding any credential record.
3. Run `./mvnw -o package`, inspect `git diff --check`, then create a focused commit after human review.

## 2. Add project Git credentials as an audited domain model

1. Create a Flyway migration for a project-scoped credential record: project id, authentication kind, optional public username, encrypted secret, creator and timestamps.
2. Implement the service so it accepts a secret only at creation/update time, encrypts it with `SecretCipher`, never exposes the ciphertext through templates or API responses, and writes audit events for creation, replacement and removal.
3. Add an administrator-only settings page for a project. Show whether a credential is configured, but never reveal or prefill it.
4. Test authorization, encryption-at-rest, audit entries and failure when no master key is configured.

## 3. Implement controlled Git mirrors and disposable worktrees

1. Define a workspace root property and validate every derived path is below it.
2. Add a Git-process abstraction with explicit executable allowlisting, timeout, bounded output and redaction of credential-bearing URLs.
3. Clone/fetch a bare mirror per project without putting secrets in repository URLs or command-line arguments; use a short-lived askpass mechanism instead.
4. Create a worktree per agent run from the mirror and record its lifecycle. Cleanup must only affect a known run directory.
5. Test path traversal rejection, failed clone diagnostics without secret disclosure, timeout handling and cleanup boundaries.

## 4. Build the first controlled agent tool slice

1. Define tool descriptors with JSON schemas for list-files, read-file, search, create-patch, show-diff and run-tests.
2. Add workspace confinement, file-size/output limits and content hashes for read-then-patch conflict detection.
3. Model a change proposal and explicit approval state; do not apply patches until approval is recorded.
4. Expose agent-run events through REST plus Server-Sent Events and persist an audit trail.

## 5. Verification discipline for every slice

1. Add focused unit/integration tests first, then run `./mvnw -o test` and `./mvnw -o package`.
2. Run `git diff --check` before handoff.
3. Persist each verified milestone in Hindsight and keep this file current if sequencing changes.
