package tech.wenisch.petri.agent.run;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/** Runs one approved, policy-built test command and returns its bounded result. */
interface TestExecutor {
  TestCommandResult run(
      UUID projectId, Path worktree, TestCommand command, TestSandboxProfileView profile)
      throws IOException;

  /** Runs with live output reporting where the executor supports it. */
  default TestCommandResult run(
      UUID projectId, Path worktree, TestCommand command, TestSandboxProfileView profile,
      java.util.function.Consumer<String> progress) throws IOException {
    return run(projectId, worktree, command, profile);
  }
}
