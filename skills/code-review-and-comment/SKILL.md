---
name: code-review-and-comment
description: Review a pull request with the code-review method and publish the review as a comment on it.
extends: code-review
context: pull_request
produces: review
---

# Code review and comment

Follow the `code-review` skill for the review itself. This skill only changes what happens with the
result: Petri renders your submitted review as a Markdown comment and posts it on the pull request.

- Write the `summary` for the pull request author, who reads it on the pull request. Lead with the
  judgment (good to merge, merge after fixes, or not ready) and why.
- Each finding becomes a section of that comment, so make its title and body understandable without
  the diff open next to it.
- Do not address the comment to anyone by `@mention`, and do not mention Petri or the automation; the
  comment is posted as the connected Git account.
- The comment is the only output. You cannot push, approve or merge anything.
