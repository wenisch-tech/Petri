package tech.wenisch.petri.automation;

public enum AutomationTrigger {
  PULL_REQUEST("When a pull request is opened or updated"),
  CRON("On a schedule"),
  /** A person started it from a pull request; never configured on a project automation. */
  MANUAL("Started manually");

  private final String label;

  AutomationTrigger(String label) { this.label = label; }

  public String getLabel() { return label; }

  /** The triggers an automation can be configured with. */
  public static AutomationTrigger[] configurable() {
    return new AutomationTrigger[] {PULL_REQUEST, CRON};
  }
}
