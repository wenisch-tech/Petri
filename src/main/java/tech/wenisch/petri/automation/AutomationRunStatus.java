package tech.wenisch.petri.automation;

public enum AutomationRunStatus {
  QUEUED,
  RUNNING,
  AWAITING_APPROVAL,
  POSTED,
  SKIPPED,
  FAILED;

  public boolean active() {
    return this == QUEUED || this == RUNNING;
  }
}
