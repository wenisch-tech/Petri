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

## Commit dialog and generated messages

The commit dialog lists every file the commit will contain with a change indicator (status and
`+added −removed` line counts). It shows the **staged** changes; ticking *Stage all changes before
committing* adds the unstaged and untracked changes under *Will be staged*, and unticking it shows
how many changes stay out of the commit.

**Generate** (the sparkles button above the message) asks the model for a message from those changes.
The generation follows the `conventional-commit` skill (`skills/conventional-commit/SKILL.md`), which
encodes the [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/)
specification: type, optional scope, imperative description up to 72 characters, optional body and
breaking-change footer. The model receives the file list, a bounded diff (30,000 characters; new
untracked files contribute a short preview) and the last eight commit subjects for style and scopes.
It uses the model the workspace chat uses: the user's saved choice for the project (the model
selector of the chat) while it is still usable, otherwise the same fallback as the chat. It returns
text only and never commits.

The message is previewed automatically: opening the commit dialog pre-fills the message field with a
suggestion (unless text is already there), and ticking or unticking *Stage all changes* regenerates
a suggestion that was not edited. **Generate** produces a fresh one at any time. The suggestion is
only text in the field to review and edit; the commit happens when you press *Commit*. Edit the
skill file to change the convention.

## Ahead and behind indicator

The git bar shows `↑ahead ↓behind` for the active worktree, and the same counts appear next to the
branch name in the worktree selector (only the non-zero ones). They are measured against the
branch's upstream. A branch that was never pushed has no upstream branch to compare with, so *ahead*
counts the commits that exist on no remote branch yet: a first commit on a new branch shows `↑1`.

## Git bar and project pages in the workspace

Under the application header the project has three **tabs**: *Workspace* (explorer, editor,
terminal), *Automations*, *Runs* and *Settings*. The git bar sits next to them and is only visible on the
Workspace tab: the branch selector and *New branch* on the left, then the indicators (`↑ ↓`,
clean/changes, rebase) and the git actions Fetch, Pull, Rebase, Push, Commit and More. There is one
worktree per branch, so there is no separate worktree selector.

The workflow visualization on the right of the header spans both the tab row and the git row, so it
has the full header height and stays visible on every tab.

Automations and Settings open **in place**: the explorer and editor are replaced by the project
page while the chat stays where it is. Forms on these pages (adding an automation, saving a
credential, syncing the mirror) are submitted in the background and the page refreshes with its
confirmation message; links between the two pages switch the view. The *Workspace* tab returns to the explorer and
editor. The open page is kept in the address as
`?view=automations` or `?view=settings`.

The standalone pages `/projects/{id}/automations` and `/projects/{id}/settings` still exist and are
what the project list links to. They render the same content with the usual application chrome; the
workspace requests it with `?embedded=true` to receive only the content.

### Runs tab

*Runs* lists the agent runs of this project only (runs on any of its worktrees, including removed
ones, started by the signed-in user), newest first and limited to 200. Chats and workflow stages
are one list with a *Type* column; filters above the table narrow it by type, status and a text
search over prompt and model, without reloading. *Inspect* opens the run detail page. The global
list of all runs remains at `/runs`; the project list is also available standalone at
`/projects/{id}/runs`.

The pages shown in the workspace use the whole panel width with a 20px gutter.

### Run details and breadcrumb

Opening a run from the *Runs* tab shows its detail page in the same panel (the standalone
`/runs/{id}` page is unchanged). Approving or rejecting its proposals and tests works in place.

The breadcrumb in the header follows the view: `Project / Workspace`, `Project / Automations`,
`Project / Runs` and `Project / Runs / Run 1a2b3c4d` while a run is open. The project name and
*Runs* in the trail are links back. The open run is kept in the address (`?view=runs&run=<id>`).

