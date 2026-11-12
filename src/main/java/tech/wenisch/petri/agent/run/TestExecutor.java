package tech.wenisch.petri.agent.run;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import tech.wenisch.petri.workspace.WorkspaceHost;

/** Runs one approved, policy-built test command and returns its bounded result. */
interface TestExecutor {
  TestCommandResult run(
      WorkspaceHost host, UUID projectId, Path worktree, TestCommand command, TestSandboxProfileView profile)
      throws IOException;

  /** Runs with live output reporting where the executor supports it. */
  default TestCommandResult run(
      WorkspaceHost host, UUID projectId, Path worktree, TestCommand command, TestSandboxProfileView profile,
      java.util.function.Consumer<String> progress) throws IOException {
    return run(host, projectId, worktree, command, profile);
  }
}
