# Git mirrors and worktrees

## Storage layout

Below `PETRI_WORKSPACE_ROOT`, Petri maintains:

```text
mirrors/<project-id>.git
runs/<project-id>/<worktree-id>/
tmp/
```

The mirror is a bare clone used as the source for disposable detached worktrees. Agent tools only receive a persisted active worktree ID and cannot select arbitrary filesystem paths.

## Repository setup

1. Register the repository at `/projects`.
2. Open project settings.
3. Add credentials if an HTTPS remote requires them.
4. Clone or synchronize the mirror.
5. Create a worktree from `/chat` or the worktree API.

Git credentials are delivered through a temporary owner-only askpass helper. They are not embedded in repository URLs or command arguments. Stored credentials are rejected for non-HTTPS repositories.

## Default branch resolution

When a caller supplies a revision, Petri uses that explicit revision and lets Git reject it if it does not exist.

When no revision is supplied, Petri resolves the worktree revision in this order:

1. The project's configured default branch, if it resolves to a commit in the mirror.
2. The branch referenced by the bare mirror's symbolic `HEAD`.
3. A resolvable detached `HEAD`.

This permits repositories whose default is `master`, `develop`, or another name even when a project was initially created with the suggested `main` value. An empty repository has no resolvable revision and cannot produce a worktree until it contains a commit.

## Synchronization

The initial operation is `git clone --mirror`. Later synchronization uses `git remote update --prune`. Existing disposable worktrees are not silently moved to a newer commit; create a new worktree when a fresh revision is required.

## Removal

Petri verifies that a persisted worktree path exactly matches the UUID-derived safe path before removal. Symlinked worktree roots are rejected. Removal uses Git's forced worktree cleanup and then marks the database record as removed.

## Troubleshooting

### Mirror must be synchronized

Create the mirror from project settings before preparing a worktree.

### Explicit revision is invalid

Verify the revision exists after synchronization. Automatic mirror-`HEAD` fallback is intentionally used only when no revision was explicitly requested.

### Private clone fails

Confirm the URL uses HTTPS when using stored credentials, then replace the credential and synchronize again. Petri redacts stored secret values and URL user information from captured Git output.
