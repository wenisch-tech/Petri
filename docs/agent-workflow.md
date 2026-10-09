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
