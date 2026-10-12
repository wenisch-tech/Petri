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

If the review is `APPROVED` but no executed test passed, or not every acceptance criterion is verified
(next section), the workflow does not loop back to the implementer. It waits for the user at the final
gate, who can approve and finish without another run (`POST /workflows/{id}/final/approve`). Any other
gate failure still triggers rework or `HUMAN_HOLD`.

### Acceptance criteria and verification

Every acceptance criterion of a plan has a stable id (`AC1`, `AC2`, ...). The planner returns
`"acceptanceCriteria":[{"id":"AC1","text":"..."}]`; plain strings from older plans are numbered by
position. When a plan is revised, the planner is shown the previous plan and keeps the id of every
criterion whose meaning did not change.

The reviewer reports `planCoverage`, one entry per criterion:

```json
{"criterionId":"AC1","status":"MET|PARTIAL|NOT_MET",
 "evidence":[{"type":"TEST","ref":"<test run id>"},{"type":"FILE","ref":"<changed file path>"}],
 "note":"..."}
```

Petri checks each citation against what really happened instead of trusting the model. The reviewer is
given the workflow's test run ids and changed files and may cite only those.

| Status | Meaning |
| --- | --- |
| `VERIFIED` | Reviewer says `MET` and at least one cited test run passed or cited file was applied and still matches. |
| `CLAIMED` | Reviewer says `MET` but no citation could be confirmed (none cited, unknown id, file not changed). |
| `PARTIAL` | Reviewer says the criterion is only partly met. |
| `NOT_MET` | Reviewer says `NOT_MET`, or every cited test failed or timed out. |
| `UNVERIFIED` | Not covered yet, or the review is older than the latest plan or implementation changes. |

The status is computed on read from the latest plan, review, task evidence and live test runs, so it
changes as soon as new evidence exists. When the final gate would pass and the review is approved but a
criterion is not `VERIFIED`, the workflow waits for the user (naming the open criteria) instead of
completing on its own.

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

- **Workflow view (top panel):** the workflow as a **Petri net**, its status, attempt/run counts and
  the **Cancel task** button. A finished workflow stays pinned in the *Done* state until a newer
  workflow starts and shows **Open merge request** (placeholder, not implemented yet) instead of
  Cancel. Click the net to open the full view with goals, evidence and the firing history.
- **Chat:** live actions and the conversation only: tool calls and stage start/finish events with
  timestamps, agent replies labelled by role, and artifacts (plan, task diff, review, summary) at the
  position where they were created. Plan approval, clarification questions and final approval appear
  at the bottom only while they await the user.
- **Reading the net:** circles are places and rectangles are transitions (gates, agents, or you).
  The coloured token marks where the workflow is: blue while an agent works, amber when it waits for
  you, red on a hold, green when done. A transition that has fired is outlined green; dashed arcs are
  rework and exception paths with a counter of how often they fired (`rework ×2`). Each acceptance
  criterion gets its own row: an open place, a verify transition driven by the review, and a verified
  place that the final gate consumes. A solid dot means open, a ring means claimed but unconfirmed,
  half-filled means partial, a cross means not met, and a filled green token means verified. The final
  gate can only complete on its own when every verified place is marked.
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

## Verification guidance skill

Environment rules that every role should follow live in one editable skill,
`skills/workflow-verification/SKILL.md`, and are added automatically to the instructions of the
orchestrator, planner, implementer and reviewer (and to direct runs). Today it covers:

- **Busy ports.** `Address already in use` and similar are environmental, not defects. The
  implementer verifies on another port and keeps the application's default unchanged; the planner
  writes endpoint criteria independent of the port; reviewers and gates do not request changes, hold
  or mark a criterion not met because of a busy port or an alternate-port check.
- Alternate-port verification is reported under `tests`, never under `deviations`.
- Stopping servers started for verification, retrying transient downloads once, and building
  without a wrapper using an installed `mvn`/`gradle`.

Edit the file to add further tweaks; no code change is needed. Workflows snapshot role instructions
when they start, so a change applies to workflows started afterwards.

