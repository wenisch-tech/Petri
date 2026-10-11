package tech.wenisch.petri.agent.tool;

import java.util.UUID;

public record ToolExecutionContext(
    UUID worktreeId,
    UUID agentRunId,
    tech.wenisch.petri.agent.orchestration.AgentWorkflowRole workflowRole) {
  public ToolExecutionContext(UUID worktreeId) {
    this(worktreeId, null, null);
  }

  public ToolExecutionContext(UUID worktreeId, UUID agentRunId) {
    this(worktreeId, agentRunId, null);
  }

  public ToolExecutionContext {
    if (worktreeId == null) throw new IllegalArgumentException("Worktree id is required");
  }
}
