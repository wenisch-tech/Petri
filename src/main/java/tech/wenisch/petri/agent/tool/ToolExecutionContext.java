package tech.wenisch.petri.agent.tool;

import java.util.UUID;

public record ToolExecutionContext(
    UUID worktreeId,
    UUID agentRunId,
    tech.wenisch.petri.agent.orchestration.AgentWorkflowRole workflowRole,
    UUID automationRunId,
    java.util.Set<String> automationTools) {
  public ToolExecutionContext(
      UUID worktreeId, UUID agentRunId,
      tech.wenisch.petri.agent.orchestration.AgentWorkflowRole workflowRole, UUID automationRunId) {
    this(worktreeId, agentRunId, workflowRole, automationRunId, null);
  }

  public ToolExecutionContext(UUID worktreeId) {
    this(worktreeId, null, null, null, null);
  }

  public ToolExecutionContext(UUID worktreeId, UUID agentRunId) {
    this(worktreeId, agentRunId, null, null, null);
  }

  public ToolExecutionContext(
      UUID worktreeId,
      UUID agentRunId,
      tech.wenisch.petri.agent.orchestration.AgentWorkflowRole workflowRole) {
    this(worktreeId, agentRunId, workflowRole, null, null);
  }

  public ToolExecutionContext {
    if (worktreeId == null) throw new IllegalArgumentException("Worktree id is required");
  }
}
