package tech.wenisch.petri.automation;

public enum AutomationRunStatus {
  QUEUED,
  RUNNING,
  AWAITING_APPROVAL,
  /** The review is done and was deliberately not published (review-only automations). */
  REVIEWED,
  /** The improvement workflow is working on the pull request's branch. */
  IMPROVING,
  POSTED,
  SKIPPED,
  FAILED;

  public boolean active() {
    return this == QUEUED || this == RUNNING;
  }
}
