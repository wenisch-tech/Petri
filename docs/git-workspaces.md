# Git mirrors and worktrees

## Storage layout

Below `PETRI_WORKSPACE_ROOT`, Petri maintains:

```text
mirrors/<project-id>.git
runs/<project-id>/<worktree-id>/
tmp/
```

The mirror is a bare repository used as the shared Git directory for detached legacy worktrees and branch-bound workspace worktrees. Agent tools only receive a persisted active worktree ID and cannot select arbitrary filesystem paths.

## Repository setup

1. Register the repository at `/projects`.
2. Open project settings.
3. Add credentials if an HTTPS remote requires them.
4. Clone or synchronize the mirror.
5. Open the project workspace and choose or create a branch.

Git credentials are delivered through a temporary owner-only askpass helper. They are not embedded in repository URLs or command arguments. Stored credentials are rejected for non-HTTPS repositories.

## Default branch resolution

When a caller supplies a revision, Petri uses that explicit revision and lets Git reject it if it does not exist.

When no revision is supplied, Petri resolves the worktree revision in this order:

1. The project's configured default branch, if it resolves to a commit in the mirror.
2. The branch referenced by the bare mirror's symbolic `HEAD`.
3. A resolvable detached `HEAD`.

This permits repositories whose default is `master`, `develop`, or another name even when a project was initially created with the suggested `main` value. An empty repository has no resolvable revision and cannot produce a worktree until it contains a commit.

## Branches and synchronization

The initial operation uses a bare mirror clone. On the first explicit fetch or branch write, Petri separates local branches under `refs/heads/*` from cached remote branches under `refs/remotes/origin/*`. Heads imported from an older mirror are first retained under `refs/petri/mirror-backup/*`. Later synchronization fetches `origin` with prune and never performs an implicit network request while rendering a page.

Each managed local branch has at most one active branch-bound worktree. Choosing a local branch opens that worktree or creates it. Choosing a cached remote-only branch creates a same-named local tracking branch and its worktree. Existing records remain detached legacy worktrees and are not silently rebound or switched.

The Git bar below the workspace topbar provides cached local/remote branches, worktree selection, ahead/behind and dirty state, Fetch, fast-forward-only Pull, Rebase, Push, Commit and branch actions. The Changes panel separates conflicts, staged, unstaged and untracked paths. Its diffs use Monaco and can inspect staged or unstaged content independently.

Each change row, each section and the whole panel offer **Revert** (single change, section, or all changes at once) in addition to stage/unstage. Reverting asks for confirmation and cannot be undone: tracked files are restored from `HEAD`, newly added files are removed and untracked files are deleted.

Git error messages appear in a dismissible popup at the top of the workspace.

Pull and rebase require a clean branch-bound worktree. Push also requires a clean worktree. Stage, unstage, revert, commit and push are blocked while agent or test work is pending or executing, or while a workflow is actively running on the worktree. A workflow on hold, waiting for you, or finished does not block them. Rebase conflicts remain in the worktree for editing and must be staged before Continue; Abort restores the pre-rebase state. Force pushing is available only as an explicit `--force-with-lease` confirmation.

Remote operations use the existing encrypted project credential through the temporary askpass helper. Petri supports the single configured remote `origin` in this workflow.

## Removal

Petri verifies that a persisted worktree path exactly matches the UUID-derived safe path before removal. Symlinked worktree roots are rejected. Removal uses Git's forced worktree cleanup and then marks the database record as removed.

The default branch and any branch with an active worktree are protected from deletion. Deleting an unmerged local branch requires an explicit force option and exact branch-name confirmation. Remote deletion separately requires the exact branch name.

## Troubleshooting

### Mirror must be synchronized

Create the mirror from project settings before preparing a worktree.

### Explicit revision is invalid

Verify the revision exists after synchronization. Automatic mirror-`HEAD` fallback is intentionally used only when no revision was explicitly requested.

### Private clone fails

Confirm the URL uses HTTPS when using stored credentials, then replace the credential and synchronize again. Petri redacts stored secret values and URL user information from captured Git output.
