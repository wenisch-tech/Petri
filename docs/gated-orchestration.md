# Gated multi-agent orchestration

New workspace chats default to **Workflow** mode; existing chats and run APIs remain **Direct** for
compatibility.

## Stage progression

```text
Orchestrator intake → Planner → Orchestrator plan gate → User plan approval
  → Implementer → Orchestrator implementation gate → Reviewer
  → Orchestrator final gate → Completed
```

The coordinator is a deterministic state machine. Model output is validated evidence and cannot
change workflow state directly. A malformed verdict, exhausted attempt budget, unresolved patch
conflict or a deterministic safety violation fails closed into `HUMAN_HOLD`. Repository uncertainty
and reasonable non-destructive defaults are surfaced as plan warnings for the user to decide.

The planner may ask up to five single-select, multi-select or free-text questions. Approval is
recorded against the exact plan revision; feedback or later task guidance invalidates it and returns
the task to planning. The implementer creates only proposals. Plan approval authorizes automatic
application of this workflow's patches and sandboxed tests, but never commits, pushes, editor writes
or unrelated runs. The reviewer and final gate receive the approved plan, canonical task diff, final
file hashes and test evidence.

### Final gate, summary and missing tests

The final gate also writes a Markdown **summary** of what was done (changes, touched files, test
evidence, caveats). It is stored with the final verdict and shown in the chat when the workflow
completes.

If the review is `APPROVED` but no executed test passed and the gate does not pass, the workflow does
not loop back to the implementer. It waits for the user at the final gate, who can approve without
executed tests (`POST /workflows/{id}/final/approve`) and finish without another run. Any other gate
failure still triggers rework or `HUMAN_HOLD`.

### Retry and attempt budgets

The per-role attempt limit counts attempts since the last manual retry. A manual **Retry** on a held
workflow records a `USER_RETRIED` transition and grants a fresh attempt budget, so a workflow that
stopped because it exhausted its attempts can always be restarted. Automatic stopping is unchanged.

### Automatic approval

Package installs requested through `install_packages` by any stage are never covered by plan
approval; they need user approval or the project's explicit auto-approve setting.

Plan approval authorizes automatic application of the implementer's patches and tests. Approval
listeners run in their own transaction so the applied status is persisted. Before judging evidence,
the coordinator settles any covered action that is still open; a file that already holds the exact
approved content is recognized as applied. A conflict is ignored once a later proposal for the same
path was applied.

## Workspace presentation

- **Workflow view (top panel):** the stage rail, status, attempt/run counts and the **Cancel task**
  button. A finished workflow stays pinned in the *Done* state until a newer workflow starts and
  shows **Open merge request** (placeholder, not implemented yet) instead of Cancel.
- **Chat:** live actions and the conversation only: tool calls and stage start/finish events with
  timestamps, agent replies labelled by role, and artifacts (plan, task diff, review, summary) at the
  position where they were created. Plan approval, clarification questions and final approval appear
  at the bottom only while they await the user.
- **Guide active task:** a composer checkbox, enabled by default while a workflow is active. Checked,
  the message is sent as guidance to the running task; unchecked, it is queued as a new task.

## Roles and model profiles

The workflow icon in the workspace configures Orchestrator, Planner, Implementer and Reviewer
independently. Per-user/per-project profiles include provider, model, generation/context budgets,
attempt limit and a prompt addendum. Petri snapshots those values when the task is submitted.

| Role | Server-enforced tools |
| --- | --- |
| Orchestrator | read, search, diff and skills |
| Planner | repository reads, search and skills |
| Implementer | reads, search, skills, patch/file proposals and test proposals |
| Reviewer | repository reads, search and skills |

Prompt addenda cannot expand permissions. Every role starts a new provider session; roles exchange
only persisted, validated artifacts, not response IDs or hidden reasoning.

## Worktrees, branches and queueing

An active workflow reserves its worktree. Direct runs are blocked for as long as the reservation
exists. Editor saves, file CRUD and mutating Git actions are blocked only while the workflow is
executing (`RUNNING`, `PENDING` or `CANCELLING`); a workflow that is waiting for the user, on
`HUMAN_HOLD` or finished leaves the worktree free to edit. Browsing and diff inspection always remain
available. Further chat messages are
persisted as ordered queued workflows. A queued task captures its own baseline only when it becomes
active.

On a default branch the workspace requires an explicit choice: reserve that branch, or create a
feature branch and child chat. The child receives a bounded parent-chat handoff. Petri captures dirty
starting state through a temporary Git index and hidden baseline ref without changing the real index
or worktree. Final evidence includes only workflow-touched paths. External changes to an applied path
are detected by its SHA-256 hash.

## Cancellation and recovery

Workflows, immutable role snapshots, typed artifacts and append-only transitions are durable.
Internal stage runs use normal execution leases. Recovery resumes expired runs and repairs the short
gap between a committed stage result and launching the next stage idempotently.

Cancellation never reverts applied changes. Running work first enters `CANCELLING`; the reservation
is released only after the stage reaches a safe terminal boundary. Petri then removes the temporary
baseline ref and starts the next queued task.
