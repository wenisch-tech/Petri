# Automations

An automation is a fixed workflow with a goal that Petri starts by itself when a trigger fires. It
inherits a skill from `skills/`, runs unattended and reports back. Automations are configured **per
project** at `/projects/{projectId}/automations`, reachable from the robot icon on the project list,
the workspace and project settings. Users cannot define their own skills yet.

**Add automation** opens a dialog:

1. **Starts**: either *When a pull request is opened or updated* or *On a schedule* (cron). It is one
   or the other; the cron field only appears for the schedule. To use both, add two automations.
2. **Skill**: what runs, one of the three review automations below.
3. **Complete without approval**: when ticked (the default) the result is acted on right away; when
   unticked it is held until a project owner approves it. This applies to every skill, so future
   actions use the same switch. For code review it controls whether the comment is posted
   immediately or kept as a draft.
4. **Enabled**, and optionally a model provider, model and maximum model turns.

A project can have one automation per skill and trigger. Disabled automations keep their settings;
removing one keeps its past runs.

| Automation | Goal | Skill |
| --- | --- | --- |
| **Code review** | Review a pull request and say whether it is good to merge. Nothing is posted. | `skills/code-review/SKILL.md` |
| **Code review and comment** | The same review, posted as a comment on the pull request. | `skills/code-review-and-comment/SKILL.md` |
| **Code review and improve** | Review, post the comment, then fix the findings on the pull request's own branch. | `skills/code-review-and-improve/SKILL.md` |

| **Vulnerability scan** | Scan a pull request's head with Trivy and keep the findings for its branch. Nothing is posted. | `skills/vulnerability-scan/SKILL.md` |
| **Vulnerability scan and comment** | The same scan, posted as a comment (table and details) on the pull request. | `skills/vulnerability-scan-and-comment/SKILL.md` |
| **Vulnerability scan and improve** | Scan, post the comment, then fix the findings (for example upgrade dependencies) on the pull request's own branch. | `skills/vulnerability-scan-and-improve/SKILL.md` |

The three code review automations share the review method of the `code-review` skill, the three
vulnerability scans the method of the `vulnerability-scan` skill; the other skills of each family
only add to it. Every kind is configured like any other (trigger, approval, model) and can be
started on one pull request with **Let Petri work on this**.
Every kind finds pull requests, reviews new commits and handles re-reviews the same way; they differ
in what happens with the result.

### Code review (review only)

The review is stored on the run and shown in *Recent runs* (**Review**) and on the run page, with the
status *Reviewed*. It is never posted and never changes anything, which makes it a safe way to try
an automation or to collect reviews for a team that wants to read them in Petri.

### Vulnerability scans

A scan automation checks out the head of the pull request like a review, then runs the
`vulnerability-scan` method unattended with the read-only tools plus `scan_vulnerabilities` and
`submit_vulnerability_report`: Trivy resolves the project's dependencies, the agent triages and
groups the findings and saves them as the table of finding, identifier, severity, where, state
today and target state. Petri then

1. keeps the table for the pull request's branch, so it appears on the project's **Vulnerabilities**
   tab and in the dashboard counts,
2. renders it as the comment (a summary, the table and one collapsible block per finding; HTML is
   escaped and `@mentions` are defused as for reviews) and posts it, or keeps it as a draft, as the
   approval setting says.

*Vulnerability scan* stops after step 1 (status *Reviewed*). *Vulnerability scan and improve*
continues exactly as *Code review and improve* below: the `CRITICAL`, `HIGH` and `MEDIUM` findings
are the task of a workflow on the pull request's branch, `LOW` and `INFO` are left alone, there are
at most two automatic rounds, and a pushed fix is scanned again as a new commit. The target state of
each finding is what the workflow is asked to reach. A scan is never compared with an earlier one.

### Code review and improve

1. The review is posted as for *Code review and comment*, with one sentence appended: either that
   Petri will now try to fix the findings, or why it will not (nothing to fix, fork without
   maintainer edits, no write access, the branch moved, or the limit of two automatic rounds per pull
   request was reached).
2. When there are `BLOCKER`, `MAJOR` or `MINOR` findings, Petri opens the pull request's head branch
   in a worktree and starts the normal **Orchestrator → Planner → Implementer → Reviewer workflow**
   there, with the findings as its task. `NIT` findings are not acted on.
3. With **Complete without approval** ticked the whole run is unattended: Petri approves the plan and
   the final gate itself, commits the result (`fix: address code review findings`) and pushes it to
   the pull request's branch, then comments with the commit. The pushed commit is reviewed again
   like any new commit. If the planner has questions, the workflow is held, is cancelled or takes
   more than three hours, the run ends as *Failed* with the reason, nothing is pushed and the work
   so far stays in the workspace.
4. With it unticked the review is a draft first. After a person approves it the comment is posted
   and the workflow starts, then waits for its normal approvals in the workspace chat. Petri does
   not commit or push by itself in this mode.

Pull requests from forks can be fixed on **GitHub** when the author allowed edits by maintainers
(Petri then pushes to the fork's branch with the project's Git credential). On other providers, and
for forks without that permission, the review comment explains that the fix has to be made by the
author. The branch must not be open in a workspace; close it first so edits do not collide.

## Triggers

| Trigger | Behavior |
| --- | --- |
| **Pull request** | Petri lists open pull requests of the project's repository through the connected Git account about every two minutes (`PETRI_AUTOMATION_POLL_INTERVAL_MS`). A pull request is reviewed when it was opened or updated after the automation was enabled. Drafts are skipped. Nothing needs to reach Petri from the Git provider, so no webhook is required. |
| **Cron** | On the configured schedule (UTC, five or six fields) every open pull request without a review for its latest commit is reviewed. |

Every head commit is reviewed once. Failed or skipped runs are retried with **Review open pull
requests now**.

**Drafts are never reviewed.** GitHub and GitLab draft pull requests, and Forgejo pull requests whose
title starts with `WIP:`, `[WIP]`, `Draft:` or `[Draft]`, are skipped at every poll. The state is
checked again right before a queued review starts, so a pull request converted to a draft meanwhile
(or closed, or pushed to) is skipped as well. Marking a draft ready makes it eligible again.

### New commits

Pushing to a reviewed pull request produces a **new comment** for the new head; earlier comments are
left alone. The new review is a re-review: the agent receives the previous review's numbered
findings and the diff between the previously reviewed commit and the new head, and has to account
for every previous finding as fixed or still open. The comment then contains a *Since the previous
review* section with the counts of resolved, still open and new findings and the overall result:

| Result | Meaning |
| --- | --- |
| Improved | The severity-weighted sum of open findings (blocker 8, major 4, minor 1, nit 0) went down. |
| Worse | It went up. |
| Mixed | Same weight, but findings were fixed and others introduced. |
| Unchanged | Same findings as before. |

Petri derives the result from the findings, so it can be checked against the numbers next to it. If
the previous head is gone from the mirror (force push), only the full diff is used. A draft review
still awaiting approval is marked *Skipped* when a newer commit is reviewed.

### Acknowledgement

When a review starts Petri adds an 👀 (`eyes`) reaction to the pull request: a reaction on GitHub and
Forgejo, an award emoji on GitLab. This is best effort; a token without permission to react only
produces a log message, the review continues.

Automations need a project that was imported from a connected GitHub, GitLab or Forgejo account.
Projects that use plain repository credentials have no provider API to poll.

## How a review runs

For each pull request Petri:

1. synchronizes the mirror and fetches the pull request head (also for forks),
2. checks the head out in a throw-away worktree and computes the diff against the base branch,
3. starts one agent run with the review skill of the automation and the diff,
4. renders the structured result into a Markdown comment and posts it (or keeps it as a draft),
5. removes the worktree.

The agent can only read: `list_files`, `read_file`, `search_files`, `get_pull_request` (the pull
request's description, discussion, changed files and CI runs, read-only) and `submit_review`. It cannot
patch files, run commands or tests, install packages or reach the network. `submit_review` takes a
summary, a verdict (`APPROVE`, `COMMENT`, `REQUEST_CHANGES`) and up to 20 findings with path, line,
severity (`BLOCKER`, `MAJOR`, `MINOR`, `NIT`), body and optional suggested code. Petri validates
that finding paths are files changed by the pull request and renders the comment itself: HTML is
escaped and `@mentions` are defused, so a review cannot ping people or inject markup.

### Retrying

A review that *failed* or was *skipped* shows a **Retry** button in the recent runs. It lists the
pull request again and queues a new review of its current state: with new commits since, the new
head is reviewed. It is refused with the reason when the pull request was closed or merged, became a
draft, or its review is already queued or running. Typical use: after fixing the token permissions
or the model provider that made the review fail.

### Following a review live

Every automation run in the *Recent runs* list links to the agent run that performs it (*Follow
live* while it works, *View run* afterwards). The run page shows the live output of the model, its
turns and tool calls, and refreshes itself every few seconds while the run is active; in the
workspace it opens inside the *Runs* tab. The automations page refreshes while a review is queued
or running, and neither page refreshes while a dialog is open or a field is being edited.

If the agent ends without calling `submit_review` (for example after the loop guard took its tools
away), its final text answer is used as the review: it is posted as a comment without structured
findings, escaped like every review, and follows the same approval setting. Only when there is no
answer at all does the run fail.

### Posting

| Mode | Behavior |
| --- | --- |
| **Post automatically** (*Complete without approval* ticked, default) | The comment is posted as soon as the review is ready. |
| **Draft** (*Complete without approval* unticked) | The review waits as *Awaiting approval*; a project owner reads it and approves it on the automations page. |

Comments are posted as the connected Git account, which therefore needs permission to comment on
pull requests:

| Provider | Token permission |
| --- | --- |
| GitHub | `repo` (classic) or pull request write (fine-grained) |
| GitLab | `api` |
| Forgejo | `write:issue` (comments and reactions on pull requests), plus `write:repository` to push |

A missing permission shows as a *Failed* run with the provider's HTTP status; the review itself is
kept.

### Model and limits

The run uses the model selected for the project's **orchestrator**: the *Orchestrator* role of the
workflow role profiles (project Settings → Model settings) of the project owner,
including its output token and context window settings and reasoning options. When no role profile
was saved, the project's last chosen workspace model applies, as it does for workflows. An
automation that names a model provider and/or model in its *Model* section overrides this. The
owner's own API key is used when the provider needs a per-user key. Reviews run on a bounded pool
(`PETRI_AUTOMATION_MAX_CONCURRENT`, default 2), one at a time per project, and at most
`PETRI_AUTOMATION_MAX_PER_POLL` (default 5) new reviews start per poll. The diff handed to the model
is cut at `PETRI_AUTOMATION_MAX_DIFF_BYTES` (default 120000); the agent reads the remaining files
itself.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `PETRI_AUTOMATION_ENABLED` | `true` | Master switch for polling and schedules. |
| `PETRI_AUTOMATION_POLL_INTERVAL_MS` | `120000` | Pull request polling interval. |
| `PETRI_AUTOMATION_MAX_PER_POLL` | `5` | New reviews started per poll and project. |
| `PETRI_AUTOMATION_MAX_CONCURRENT` | `2` | Reviews running at the same time. |
| `PETRI_AUTOMATION_MAX_DIFF_BYTES` | `120000` | Diff size included in the prompt. |

## Security notes

- Reviews run without a person watching. The title, description, branch names and diff of a pull
  request are untrusted input and are delimited as data in the prompt; the tool set has no write or
  execution tools, so injected instructions can at worst distort the review text.
- The review is posted only to the pull request that triggered the run; the target is not tool
  input.
- Review content leaves Petri to the configured model provider and the Git provider. Use a local
  model provider for repositories whose code must not leave your network.
- *Code review and improve* is the only automation that changes a repository. It acts on the
  pull request that triggered it, on that pull request's own branch, never on the default branch,
  and only through the normal workflow and Git push of the project's credential. The review agent
  itself still has no write tools: the findings it wrote reach the workflow as data.
- Audit events: `PROJECT_AUTOMATION_SAVED`, `AUTOMATION_RUN_STARTED`, `AUTOMATION_COMMENT_POSTED`,
  `AUTOMATION_REVIEW_COMPLETED`, `AUTOMATION_IMPROVEMENT_STARTED`, `AUTOMATION_IMPROVEMENT_PUSHED`,
  `AUTOMATION_IMPROVEMENT_FAILED`, `AUTOMATION_RUN_FAILED`.
