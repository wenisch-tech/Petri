package tech.wenisch.petri.agent.tool;

import java.util.UUID;

public record ToolExecutionContext(UUID worktreeId, UUID agentRunId) {
  public ToolExecutionContext(UUID worktreeId) {
    this(worktreeId, null);
  }

  public ToolExecutionContext {
    if (worktreeId == null) throw new IllegalArgumentException("Worktree id is required");
  }
}
