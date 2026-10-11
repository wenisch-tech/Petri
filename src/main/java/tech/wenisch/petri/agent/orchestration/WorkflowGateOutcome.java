package tech.wenisch.petri.agent.orchestration;

public enum WorkflowGateOutcome {
  STARTED,
  PASS,
  REVISION_REQUIRED,
  HOLD,
  USER_APPROVED,
  USER_REVISED,
  USER_CANCELLED,
  RETRIED
}
