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
conflict or missing evidence fails closed into `HUMAN_HOLD`.

The planner may ask up to five single-select, multi-select or free-text questions. Approval is
recorded against the exact plan revision; feedback or later task guidance invalidates it and returns
the task to planning. The implementer creates only proposals. Plan approval authorizes automatic
application of this workflow's patches and sandboxed tests, but never commits, pushes, editor writes
or unrelated runs. The reviewer and final gate receive the approved plan, canonical task diff, final
file hashes and test evidence.

## Roles and model profiles

The workflow icon in the workspace configures Orchestrator, Planner, Implementer and Reviewer
independently. Per-user/per-project profiles include provider, model, generation/context budgets,
attempt limit and a prompt addendum. Petri snapshots those values when the task is submitted.

| Role | Server-enforced tools |
| --- | --- |
| Orchestrator | none |
| Planner | repository reads, search and skills |
| Implementer | reads, search, skills, patch/file proposals and test proposals |
| Reviewer | repository reads, search and skills |

Prompt addenda cannot expand permissions. Every role starts a new provider session; roles exchange
only persisted, validated artifacts, not response IDs or hidden reasoning.

## Worktrees, branches and queueing

An active workflow reserves its worktree. Direct runs, editor saves, file CRUD and mutating Git
actions are blocked, while browsing and diff inspection remain available. Further chat messages are
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
