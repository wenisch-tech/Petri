# Next implementation steps

Updated after the implementation audit on 2026-10-11.

## Current state

Petri now includes durable chats and queues, streaming model output, Monaco editing and diffs,
branch/worktree Git workflows, personal and global GitHub/GitLab/Forgejo instances, PAT/OAuth
connections, repository import jobs, owner-scoped projects/API keys, and rootless Podman test
sandboxes.

The audit fixed the following concrete defects:

- Git operation locks now retain a stable lock object instead of allowing a removal race.
- repository initialization leases have heartbeats and per-execution ownership; stale workers can
  no longer finish or fail a reclaimed job.
- interrupted workers no longer mark jobs they never claimed as failed.
- sandbox image, CPU and memory settings are validated before they reach Podman.
- Git staging rejects a normalized empty/root path.
- legacy chat resume/cancel redirects no longer re-read an invalid or inaccessible run after an
  error.
- the workspace explorer now supports rename and delete, including recursive confirmation and
  optimistic hashes for text, binary and oversized files.
- chat snapshot refreshes preserve the reader's scroll position, and approval failures are shown
  in the chat instead of becoming unhandled promise rejections.

Current quality gate: frontend bundles build and all 70 Java tests pass. `git diff --check` passes.

## 1. Add browser-level workspace regression tests

This is the highest-priority gap because the workspace has substantial client-side state and most
past regressions occurred in browser interaction code.

1. Add Playwright (or an equivalent browser runner) to the build.
2. Cover explorer lazy loading, create/rename/delete, Monaco tabs and drafts, Git changes/diffs,
   send/stream/reconnect behavior, bottom following and review actions.
3. Verify light/dark themes, narrow layouts and sidebar resizing.
4. Run the suite against both H2 and PostgreSQL-backed application profiles in CI.

## 2. Expand authorization and API contract coverage

1. Add MockMvc tests for every workspace file, Git, chat, SSE and connected-account endpoint.
2. Assert that foreign and missing resource IDs consistently return 404 without revealing whether
   the resource exists.
3. Add tests for binary/oversized file metadata, stale SHA conflicts and recursive delete
   confirmation.
4. Add rate limits for API keys, chat submission, provider catalog calls and expensive Git/file
   operations.

## 3. Make coordination safe for multiple Petri instances

The queue and import leases are database-backed, but Git operation locks remain process-local.

1. Replace or augment in-memory Git locks with database advisory locks or a lease table.
2. Add two-application concurrency tests for fetch, branch mutation, worktree Git actions and
   initialization recovery.
3. Add lease/queue metrics and structured logs for claims, renewals, stale recovery and contention.
4. Add indexes for owner/chat/status queries after measuring them on a production-sized PostgreSQL
   dataset.

## 4. Harden provider integrations and dependency maintenance

1. Add end-to-end OAuth tests for state expiry, PKCE, callback replay, token rotation and concurrent
   refresh for GitHub, GitLab and Forgejo.
2. Test provider pagination and rate-limit responses over multiple pages, especially the
   server-filtered GitHub/Forgejo search path.
3. Enforce database-level uniqueness for global provider URLs as well as personal owner/URL pairs.
4. Review the two moderate npm advisories reported by `npm ci`; obtaining the detailed advisory
   report sends dependency metadata to the npm registry and therefore requires explicit approval.
5. Add automated dependency and migration compatibility checks to CI.

## 5. Complete sandbox verification

1. Resolve the image digest before execution and run the container by immutable digest, so the
   recorded snapshot is guaranteed to match the executed image.
2. Add a real rootless-Podman smoke test for Maven and Gradle, network-on/network-off profiles,
   timeout, cancellation and cleanup.
3. Define upper bounds for administrator-configurable CPU, memory, PID, timeout and output limits.
4. Expose sandbox runtime/profile readiness in health and operator diagnostics.

## 6. Product follow-ups

1. Add chat archive/delete and workspace search across chats.
2. Add reusable named model profiles and per-user generation defaults.
3. Add staging/revert support at hunk level only after the browser regression suite protects the
   existing read-only diff workflow.
4. Build the VS Code client on the existing run/SSE APIs once rate limits and API integration tests
   are in place.
