package tech.wenisch.petri.agent.orchestration;

public enum AgentWorkflowStatus {
  PENDING,
  RUNNING,
  WAITING_FOR_USER,
  HUMAN_HOLD,
  CANCELLING,
  COMPLETED,
  CANCELLED
}
