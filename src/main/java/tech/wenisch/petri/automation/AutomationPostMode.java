package tech.wenisch.petri.automation;

public enum AutomationPostMode {
  AUTO("Post the review as soon as it is ready"),
  DRAFT("Keep as a draft until a person approves it");

  private final String label;

  AutomationPostMode(String label) { this.label = label; }

  public String getLabel() { return label; }
}
