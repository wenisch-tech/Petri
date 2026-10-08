package tech.wenisch.petri.agent.tool;

import java.util.UUID;

public record ToolExecutionContext(UUID worktreeId) {
  public ToolExecutionContext {
    if (worktreeId == null) throw new IllegalArgumentException("Worktree id is required");
  }
}
