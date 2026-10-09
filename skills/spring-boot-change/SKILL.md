---
name: spring-boot-change
description: Safely inspect, modify, and verify a Spring Boot feature using Petri's approval-gated tools.
---

# Spring Boot change workflow

1. Inspect `pom.xml` or `build.gradle` and list the relevant source and test packages.
2. Search for existing controllers, services, configuration, and tests before proposing a design.
3. Read every file that will influence the change. Reuse established package, naming, error-handling, and test conventions.
4. Describe the smallest coherent implementation plan in the model response.
5. For each changed file, use the SHA-256 from the latest `read_file` result when calling `create_patch`. For a file that does not exist yet, call `create_file` with its full content instead.
6. Inspect each proposal with `show_diff`. If a proposal is stale or incorrect, read the current file again and create a replacement proposal.
7. Request `run_tests` only after the required changes have been proposed. Applying patches and executing tests still require separate human approval.
8. In the final response, distinguish clearly between proposed, approved, applied, and verified work.

Never invent file content, never bypass an approval boundary, and never claim that proposed work is already present in the worktree.
