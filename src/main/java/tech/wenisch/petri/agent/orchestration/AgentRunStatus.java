package tech.wenisch.petri.agent.orchestration;

public enum AgentRunStatus {
  PENDING,
  RUNNING,
  CANCELLATION_REQUESTED,
  COMPLETED,
  FAILED,
  BUDGET_EXHAUSTED,
  CANCELLED
}
