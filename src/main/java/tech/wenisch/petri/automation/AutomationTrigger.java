package tech.wenisch.petri.automation;

public enum AutomationTrigger {
  PULL_REQUEST("When a pull request is opened or updated"),
  CRON("On a schedule");

  private final String label;

  AutomationTrigger(String label) { this.label = label; }

  public String getLabel() { return label; }
}
