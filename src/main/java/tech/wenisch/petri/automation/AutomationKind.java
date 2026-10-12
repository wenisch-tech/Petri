package tech.wenisch.petri.automation;

import java.util.Set;

/** The built-in automations. Users configure them per project; they cannot define their own yet. */
public enum AutomationKind {
  CODE_REVIEW(
      "Code review",
      "Reviews new pull requests with the code-review skill and comments on them.",
      "code-review",
      Set.of(AutomationTrigger.PULL_REQUEST, AutomationTrigger.CRON));

  private final String title;
  private final String description;
  private final String skill;
  private final Set<AutomationTrigger> triggers;

  AutomationKind(String title, String description, String skill, Set<AutomationTrigger> triggers) {
    this.title = title;
    this.description = description;
    this.skill = skill;
    this.triggers = triggers;
  }

  public String title() { return title; }
  public String description() { return description; }
  public String skill() { return skill; }
  public Set<AutomationTrigger> triggers() { return triggers; }
  public String getTitle() { return title; }
  public String getDescription() { return description; }
}
