---
name: code-review-and-improve
description: Review a pull request with the code-review method, publish the review as a comment and hand its findings to an implementation workflow that fixes them on the pull request's branch.
---

# Code review and improve

Follow the `code-review` skill for the review itself. After you submit, Petri posts your review as a
comment and then starts an implementation workflow on the pull request's own branch. The workflow
receives your findings as its task, changes the code and pushes the result as a new commit.

Because an automated implementer acts on your findings, write them for that reader:

- Report a finding only when a change to the code can resolve it. Put questions, trade-offs and
  decisions that belong to a person in the `summary`, not in `findings`.
- Make every finding specific enough to act on without guessing: the file, the line, what is wrong
  and what the code should do instead. Add a `suggestion` when you can give the exact replacement.
- Use severity honestly. The implementer fixes `BLOCKER`, `MAJOR` and `MINOR` findings and leaves
  `NIT`s alone, so a `NIT` is never worth blocking on.
- Do not ask for new features, refactors or style changes that the pull request did not start. A
  fix must stay inside the scope of the change that is being reviewed.
- When nothing needs to change, submit an empty `findings` list with verdict `APPROVE`. No workflow
  is started then.
