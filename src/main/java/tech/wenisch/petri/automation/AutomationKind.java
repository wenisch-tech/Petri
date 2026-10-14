package tech.wenisch.petri.automation;

import java.util.Set;

/** The built-in automations. Users configure them per project; they cannot define their own yet. */
public enum AutomationKind {
  CODE_REVIEW(
      "Code review",
      "Reviews pull requests and summarises whether they are good to merge. Nothing is posted.",
      "code-review",
      false,
      false,
      Set.of(AutomationTrigger.PULL_REQUEST, AutomationTrigger.CRON)),
  CODE_REVIEW_AND_COMMENT(
      "Code review and comment",
      "Reviews pull requests and posts the review as a comment on them.",
      "code-review-and-comment",
      true,
      false,
      Set.of(AutomationTrigger.PULL_REQUEST, AutomationTrigger.CRON)),
  CODE_REVIEW_AND_IMPROVE(
      "Code review and improve",
      "Reviews pull requests, comments, then lets a workflow fix the findings and pushes the result "
          + "to the pull request's branch.",
      "code-review-and-improve",
      true,
      true,
      Set.of(AutomationTrigger.PULL_REQUEST, AutomationTrigger.CRON));

  /** The skill that carries the review method; the other review skills only add to it. */
  public static final String REVIEW_METHOD_SKILL = "code-review";

  private final String title;
  private final String description;
  private final String skill;
  private final boolean comments;
  private final boolean improves;
  private final Set<AutomationTrigger> triggers;

  AutomationKind(
      String title, String description, String skill, boolean comments, boolean improves,
      Set<AutomationTrigger> triggers) {
    this.title = title;
    this.description = description;
    this.skill = skill;
    this.comments = comments;
    this.improves = improves;
    this.triggers = triggers;
  }

  public String title() { return title; }
  public String description() { return description; }
  public String skill() { return skill; }
  /** Whether the review is published as a comment on the pull request. */
  public boolean comments() { return comments; }
  /** Whether a workflow then fixes the findings and pushes to the pull request's branch. */
  public boolean improves() { return improves; }
  public Set<AutomationTrigger> triggers() { return triggers; }
  public String getTitle() { return title; }
  public String getDescription() { return description; }
}
