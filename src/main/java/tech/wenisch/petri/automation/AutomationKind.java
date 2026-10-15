package tech.wenisch.petri.automation;

import java.util.Set;

/** The built-in automations. Users configure them per project; they cannot define their own yet. */
public enum AutomationKind {
  CODE_REVIEW(
      "Code review",
      "Reviews pull requests and summarises whether they are good to merge. Nothing is posted.",
      "code-review", "code-review", false, false),
  CODE_REVIEW_AND_COMMENT(
      "Code review and comment",
      "Reviews pull requests and posts the review as a comment on them.",
      "code-review-and-comment", "code-review", true, false),
  CODE_REVIEW_AND_IMPROVE(
      "Code review and improve",
      "Reviews pull requests, comments, then lets a workflow fix the findings and pushes the result "
          + "to the pull request's branch.",
      "code-review-and-improve", "code-review", true, true),
  VULNERABILITY_SCAN(
      "Vulnerability scan",
      "Scans pull requests for vulnerable dependencies, secrets and misconfigurations with Trivy "
          + "and keeps the findings for the branch. Nothing is posted.",
      "vulnerability-scan", "vulnerability-scan", false, false),
  VULNERABILITY_SCAN_AND_COMMENT(
      "Vulnerability scan and comment",
      "Scans pull requests with Trivy and posts the findings as a comment on them.",
      "vulnerability-scan-and-comment", "vulnerability-scan", true, false),
  VULNERABILITY_SCAN_AND_IMPROVE(
      "Vulnerability scan and improve",
      "Scans pull requests with Trivy, comments, then lets a workflow fix the findings (for example "
          + "upgrade dependencies) and pushes the result to the pull request's branch.",
      "vulnerability-scan-and-improve", "vulnerability-scan", true, true);

  /** The skill that carries the review method; the other review skills only add to it. */
  public static final String REVIEW_METHOD_SKILL = "code-review";
  public static final String VULNERABILITY_METHOD_SKILL = "vulnerability-scan";

  private final String title;
  private final String description;
  private final String skill;
  private final String baseSkill;
  private final boolean comments;
  private final boolean improves;

  AutomationKind(
      String title, String description, String skill, String baseSkill, boolean comments,
      boolean improves) {
    this.title = title;
    this.description = description;
    this.skill = skill;
    this.baseSkill = baseSkill;
    this.comments = comments;
    this.improves = improves;
  }

  public String title() { return title; }
  public String description() { return description; }
  public String skill() { return skill; }
  /** The skill with the method; this kind's own skill adds what happens with the result. */
  public String baseSkill() { return baseSkill; }
  /** Whether the review is published as a comment on the pull request. */
  public boolean comments() { return comments; }
  /** Whether a workflow then fixes the findings and pushes to the pull request's branch. */
  public boolean improves() { return improves; }
  /** Whether this kind scans for vulnerabilities rather than reviewing the change. */
  public boolean vulnerability() { return VULNERABILITY_METHOD_SKILL.equals(baseSkill); }
  public Set<AutomationTrigger> triggers() {
    return Set.of(AutomationTrigger.PULL_REQUEST, AutomationTrigger.CRON);
  }
  public String getTitle() { return title; }
  public String getDescription() { return description; }
}
