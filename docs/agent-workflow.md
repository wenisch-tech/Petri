# Agent workflow

## Lifecycle

```text
Project → mirror → disposable worktree → agent run
                                      ↘ proposals → administrator approval
```

An agent run stores the selected provider, model, generation settings, prompt, limits and creator. Ordered turns record the user prompt, model responses, tool calls, tool results and errors.

## Execution loop

1. A worker claims the run with an execution lease.
2. Petri sends the prompt and available tool definitions to the configured provider.
3. The response is persisted before any requested tool runs.
4. Tools execute one at a time and persist their result.
5. Petri continues the model conversation until it returns final text, is cancelled, fails or exhausts the turn budget.

OpenAI and LiteLLM continue through provider response IDs. Ollama and generic Chat Completions clients reconstruct complete conversation history from persisted turns.

## Approval boundaries

### File changes

`create_patch` and `create_file` never write a file. `create_patch` replaces an existing file and persists replacement content, the expected SHA-256 hash and a unified diff. `create_file` adds a file that does not exist yet; the expected hash is null and the diff is generated against an empty file. An administrator must approve and separately apply the proposal. Application becomes `CONFLICT` when the file no longer matches the expected hash, or when a file to be created already exists. Applying a creation also materializes any missing parent directories inside the worktree.

### Test runs

`run_tests` creates a proposal. After approval, Petri permits only a recognized repository wrapper command:

- `./mvnw --batch-mode --no-transfer-progress test`
- `./gradlew --no-daemon test`

The process has a timeout and bounded combined output. Run Petri in an isolated account or container when repositories are untrusted.

## Cancellation and recovery

Cancellation is checked between model calls and tools. A pending run cancels immediately; an active run records the request and stops at the next safe boundary.

Execution leases prevent two workers from continuing the same run simultaneously. If a process disappears, the persisted run can be resumed after its lease expires. Tool calls and results already stored in the database are reused rather than invented.

## Skills

Petri discovers `<skills-root>/<name>/SKILL.md`. The model initially receives only names and descriptions through `list_skills`, then calls `load_skill` when relevant. Skills provide instructions but cannot add capabilities or bypass tool and approval policy.

## Waiting for approvals

File changes (`create_patch`, `create_file`), test runs (`run_tests`) and package installs
(`install_packages`) are approval-gated proposals. After the model requests one, the run **pauses**
until the action is decided and finished:

- approved by the user, or automatically (chat automation, workflow plan approval for patches and
  tests, or the project's package auto-approve), then applied/executed;
- or **declined**.

The persisted tool result is then replaced with the final outcome (`finalStatus`, an `outcome`
sentence, test or install output) before the next model request. A decline tells the model the user
declined and that it must not retry unchanged. Several proposals from one model turn are all created
first and then awaited together. The execution lease is renewed while waiting, cancellation is
honored, and `PETRI_AGENT_APPROVAL_WAIT` (default `12h`) bounds the wait; on timeout the model is
told the request is still pending.

A run interrupted by a server restart while waiting resumes with the "pending" tool result.

## Approval settings and live output

The **shield** button below the chat opens the approval settings:

- *Project* (all chats and workflows): auto-approve **package installs** and auto-approve **agent
  terminal commands**. Both are off by default and saved per project.
- *This chat* (direct mode only): auto-apply file changes and auto-run approved tests.

Workflow plan approval already covers file changes and tests; installs and commands always ask
unless enabled in the project settings.

While a terminal command, package install or test is running its output streams into its card
(refreshed about every 1.5 s, tail only) and the newest step's output stays expanded at full size
until the next step follows.

## Loop guard

Weak models sometimes cannot make up their mind and repeat themselves: they re-read the same diff or
file and re-write the same assessment until the turn budget runs out. Petri compares every model
response with the previous ones. A response is a *repetition* when it requests the same tool calls
as the response before it, or when its text (200+ characters) is at least 80 % word-for-word
similar to one of the last three responses.

| Consecutive repetitions | What happens |
| --- | --- |
| 1 | The next request carries a loop-guard instruction: read your own last message, decide from it how to continue, do not repeat it, and answer now if it already has what is needed. |
| 2 or more | Tools are withdrawn. Tool calls the model still makes are answered with a "skipped by the loop guard" result without running, and the model must give its final response with what it has gathered. |

Every intervention is visible and measurable:

- The workflow activity feed shows a *Loop guard intervention* entry, styled like a tool call, with
  the action (repeat blocked, or tools withdrawn) and the role.
- The intervention is stored as a `LOOP_GUARD` turn of the run, and the run keeps counters
  (`loopGuardNudges`, `loopGuardWithdrawals` on the run view; columns on `agent_run`).
- Prometheus exposes `petri_agent_loop_guard_interventions_total{action,role}`.

Withdrawal applies to every tool, also in automations. A code review that ends this way still
delivers: when the agent answers in plain text without having called `submit_review`, that text is
stored and posted as the review (a comment without findings, escaped like any other review), subject
to the automation's approval setting. A tool that failed unexpectedly is logged with its stack
trace; the model sees only a generic message.

A model that makes progress (different calls, different text) is never affected, and one step in a
different direction resets the count. The turn budget still applies as the last resort.

