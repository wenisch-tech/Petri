# HTTP API

## Authentication

Create an API key at `/api-keys`, then send it as a bearer token:

```bash
export PETRI_URL=http://localhost:8080
export PETRI_API_KEY='petri_...'

curl -H "Authorization: Bearer $PETRI_API_KEY" \
  "$PETRI_URL/api/v1/identity"
```

Routes below `/api/v1/**` require an API-client principal. Browser administration and approval routes require an authenticated instance administrator and CSRF protection.

## Worktrees

```bash
# List
curl -H "Authorization: Bearer $PETRI_API_KEY" \
  "$PETRI_URL/api/v1/projects/$PROJECT_ID/worktrees"

# Create from the project's resolved default branch
curl -X POST \
  -H "Authorization: Bearer $PETRI_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{}' \
  "$PETRI_URL/api/v1/projects/$PROJECT_ID/worktrees"

# Create from an explicit revision
curl -X POST \
  -H "Authorization: Bearer $PETRI_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"revision":"feature/example"}' \
  "$PETRI_URL/api/v1/projects/$PROJECT_ID/worktrees"
```

Delete with `DELETE /api/v1/projects/{projectId}/worktrees/{worktreeId}`.

## Agent runs

Create and asynchronously start a run:

```bash
curl -X POST \
  -H "Authorization: Bearer $PETRI_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{
    "prompt":"Inspect this repository and propose the requested change",
    "provider":"OLLAMA",
    "maxModelTurns":20
  }' \
  "$PETRI_URL/api/v1/agent/worktrees/$WORKTREE_ID/runs/start"
```

Optional run fields are `model`, `maxOutputTokens`, `reasoningEffort` and `textVerbosity`.

| Method and path | Purpose |
| --- | --- |
| `POST .../runs` | Create a pending run. |
| `POST .../runs/start` | Create and asynchronously start. |
| `GET .../runs` | List runs for a worktree. |
| `GET .../runs/{runId}` | Read a run and its turns. |
| `POST .../runs/{runId}/execute` | Execute synchronously. |
| `POST .../runs/{runId}/resume` | Resume asynchronously. |
| `POST .../runs/{runId}/cancel` | Request cancellation. |
| `GET .../runs/{runId}/events` | Replay persisted run events. |
| `GET .../runs/{runId}/events/stream` | Subscribe with server-sent events. |

## Direct tool API

List descriptors with `GET /api/v1/agent/tools`.

Invoke a tool with:

```bash
curl -X POST \
  -H "Authorization: Bearer $PETRI_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"path":"README.md"}' \
  "$PETRI_URL/api/v1/agent/worktrees/$WORKTREE_ID/tools/read_file"
```

Worktree-wide events are available from `/api/v1/agent/worktrees/{worktreeId}/events` and `/events/stream`.

## Approvals

API clients may request tools and create proposals but cannot approve privileged actions. Approval, rejection, patch application and test execution are administrator operations below `/operations/**` or equivalent browser run/chat actions.

## Browser workspace Git API

The workspace uses administrator-only, CSRF-protected JSON endpoints under `/workspace/api`. These are session APIs rather than API-key routes:

| Method and path | Purpose |
| --- | --- |
| `GET /projects/{projectId}/branches` | Cached local and `origin/*` branches, upstreams and bindings. |
| `POST /projects/{projectId}/git/fetch` | Authenticated explicit fetch with prune. |
| `POST /projects/{projectId}/branches` | Create a branch and dedicated worktree. |
| `POST /projects/{projectId}/branches/open` | Open/create a local or remote-tracking worktree. |
| `POST /projects/{projectId}/branches/delete` | Confirmed local or remote deletion. |
| `GET /worktrees/{id}/git/status` | HEAD, upstream, ahead/behind, rebase and grouped changes. |
| `GET /worktrees/{id}/git/diff` | `COMBINED`, `STAGED` or `UNSTAGED` file diff. |
| `POST /worktrees/{id}/git/stage` | Stage an explicit safe path list. |
| `POST /worktrees/{id}/git/unstage` | Unstage an explicit safe path list. |
| `POST /worktrees/{id}/git/discard` | Discard staged and unstaged changes (and delete untracked files) for an explicit safe path list. |
| `POST /worktrees/{id}/git/commit` | Commit staged changes or stage all and commit. |
| `POST /worktrees/{id}/git/pull` | Fetch and fast-forward only. |
| `POST /worktrees/{id}/git/rebase` | Rebase onto an explicit cached branch or upstream. |
| `POST /worktrees/{id}/git/rebase/continue` | Continue a resolved and staged rebase. |
| `POST /worktrees/{id}/git/rebase/abort` | Abort an active rebase. |
| `POST /worktrees/{id}/git/push` | Normal push or separately confirmed force-with-lease. |
