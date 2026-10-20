package tech.wenisch.petri.automation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What an automation runs: a skill plus what is done with its result. This used to be an enum with
 * a constant per skill; it is now a value that is read from the {@code automation_action} table, so
 * a new action needs no code. The built-in actions below are the seed that is written to that table
 * at startup; custom actions are only rows.
 *
 * <p>The name is kept (it is the persisted column of automations and runs), as are the accessors
 * the pipelines use.
 *
 * @param id stable identifier, also the value of the {@code kind} columns
 * @param skill the skill that is run
 * @param baseSkill the skill whose method it builds on (the skill itself when it stands alone)
 * @param comments the result is published as a comment on the pull request
 * @param improves a workflow fixes the findings and pushes to the pull request's branch
 * @param branchBased works on a selected branch and opens its own pull request
 * @param merges the opened pull request is approved and merged when its checks pass
 * @param vulnerability scans for vulnerabilities rather than reviewing the change
 */
public record AutomationKind(
    String id, String title, String description, String skill, String baseSkill,
    boolean comments, boolean improves, boolean branchBased, boolean merges,
    boolean vulnerability, Set<AutomationTrigger> triggers, boolean builtin) {

  private static final Set<AutomationTrigger> ANY =
      Set.of(AutomationTrigger.PULL_REQUEST, AutomationTrigger.CRON);
  private static final Set<AutomationTrigger> SCHEDULED = Set.of(AutomationTrigger.CRON);

  public static final AutomationKind CODE_REVIEW = new AutomationKind(
      "CODE_REVIEW", "Code review",
      "Reviews pull requests and summarises whether they are good to merge. Nothing is posted.",
      "code-review", "code-review", false, false, false, false, false, ANY, true);
  public static final AutomationKind CODE_REVIEW_AND_COMMENT = new AutomationKind(
      "CODE_REVIEW_AND_COMMENT", "Code review and comment",
      "Reviews pull requests and posts the review as a comment on them.",
      "code-review-and-comment", "code-review", true, false, false, false, false, ANY, true);
  public static final AutomationKind CODE_REVIEW_AND_IMPROVE = new AutomationKind(
      "CODE_REVIEW_AND_IMPROVE", "Code review and improve",
      "Reviews pull requests, comments, then lets a workflow fix the findings and pushes the "
          + "result to the pull request's branch.",
      "code-review-and-improve", "code-review", true, true, false, false, false, ANY, true);
  public static final AutomationKind VULNERABILITY_SCAN = new AutomationKind(
      "VULNERABILITY_SCAN", "Vulnerability scan",
      "Scans pull requests for vulnerable dependencies, secrets and misconfigurations with Trivy "
          + "and keeps the findings for the branch. Nothing is posted.",
      "vulnerability-scan", "vulnerability-scan", false, false, false, false, true, ANY, true);
  public static final AutomationKind VULNERABILITY_SCAN_AND_COMMENT = new AutomationKind(
      "VULNERABILITY_SCAN_AND_COMMENT", "Vulnerability scan and comment",
      "Scans pull requests with Trivy and posts the findings as a comment on them.",
      "vulnerability-scan-and-comment", "vulnerability-scan", true, false, false, false, true,
      ANY, true);
  public static final AutomationKind VULNERABILITY_SCAN_AND_IMPROVE = new AutomationKind(
      "VULNERABILITY_SCAN_AND_IMPROVE", "Vulnerability scan and improve",
      "Scans pull requests with Trivy, comments, then lets a workflow fix the findings (for "
          + "example upgrade dependencies) and pushes the result to the pull request's branch.",
      "vulnerability-scan-and-improve", "vulnerability-scan", true, true, false, false, true,
      ANY, true);
  public static final AutomationKind VULNERABILITY_FIX_PR = new AutomationKind(
      "VULNERABILITY_FIX_PR", "Vulnerability fix pull request",
      "Scans a selected branch for vulnerabilities, fixes them on a new branch and opens a pull "
          + "request into the selected branch.",
      "vulnerability-fix-pr", "vulnerability-fix", true, true, true, false, false, SCHEDULED, true);
  public static final AutomationKind VULNERABILITY_FIX_AND_MERGE = new AutomationKind(
      "VULNERABILITY_FIX_AND_MERGE", "Vulnerability fix and merge",
      "The same as the fix pull request, then approves it and merges it once the checks pass and "
          + "a rescan confirms the fixes.",
      "vulnerability-fix-and-merge", "vulnerability-fix", true, true, true, true, false,
      SCHEDULED, true);

  public static final AutomationKind CVE_SCAN = new AutomationKind(
      "CVE_SCAN", "CVE scan",
      "Like the vulnerability scan, but only for known vulnerabilities (CVEs) in dependencies: no "
          + "secrets, Dockerfiles, Helm charts or other configuration. Nothing is posted.",
      "cve-scan", "vulnerability-scan", false, false, false, false, true, ANY, true);
  public static final AutomationKind CVE_SCAN_AND_COMMENT = new AutomationKind(
      "CVE_SCAN_AND_COMMENT", "CVE scan and comment",
      "Scans pull requests for CVEs in dependencies only and posts the findings as a comment.",
      "cve-scan-and-comment", "vulnerability-scan", true, false, false, false, true, ANY, true);
  public static final AutomationKind CVE_SCAN_AND_IMPROVE = new AutomationKind(
      "CVE_SCAN_AND_IMPROVE", "CVE scan and improve",
      "Scans pull requests for CVEs in dependencies only, comments, then lets a workflow upgrade "
          + "the dependencies and pushes the result to the pull request's branch.",
      "cve-scan-and-improve", "vulnerability-scan", true, true, false, false, true, ANY, true);
  public static final AutomationKind CVE_FIX_PR = new AutomationKind(
      "CVE_FIX_PR", "CVE fix pull request",
      "Scans a selected branch for CVEs in dependencies only, fixes them on a new branch and opens "
          + "a pull request into the selected branch. An open one is updated instead.",
      "cve-fix-pr", "vulnerability-fix", true, true, true, false, false, SCHEDULED, true);
  public static final AutomationKind CVE_FIX_AND_MERGE = new AutomationKind(
      "CVE_FIX_AND_MERGE", "CVE fix and merge",
      "The same as the CVE fix pull request, then approves it and merges it once the checks pass "
          + "and a rescan confirms the fixes.",
      "cve-fix-and-merge", "vulnerability-fix", true, true, true, true, false, SCHEDULED, true);

  /** The skill that carries the review method; the other review skills only add to it. */
  public static final String REVIEW_METHOD_SKILL = "code-review";
  public static final String VULNERABILITY_METHOD_SKILL = "vulnerability-scan";
  public static final String FIX_METHOD_SKILL = "vulnerability-fix";

  private static final List<AutomationKind> BUILTIN = List.of(
      CODE_REVIEW, CODE_REVIEW_AND_COMMENT, CODE_REVIEW_AND_IMPROVE, VULNERABILITY_SCAN,
      VULNERABILITY_SCAN_AND_COMMENT, VULNERABILITY_SCAN_AND_IMPROVE, VULNERABILITY_FIX_PR,
      VULNERABILITY_FIX_AND_MERGE, CVE_SCAN, CVE_SCAN_AND_COMMENT, CVE_SCAN_AND_IMPROVE, CVE_FIX_PR,
      CVE_FIX_AND_MERGE);

  /** What is known right now: the built-in actions plus the custom ones loaded from the table. */
  private static volatile List<AutomationKind> catalog = BUILTIN;

  public AutomationKind {
    triggers = Set.copyOf(triggers);
  }

  /** Built-in actions, to be written to the table at startup. */
  public static List<AutomationKind> builtins() {
    return BUILTIN;
  }

  /** Called with every action the table holds; the built-in constants are kept as they are. */
  public static void install(List<AutomationKind> loaded) {
    List<AutomationKind> all = new ArrayList<>(BUILTIN);
    for (AutomationKind action : loaded) {
      if (all.stream().noneMatch(existing -> existing.id().equals(action.id()))) all.add(action);
    }
    catalog = List.copyOf(all);
  }

  public static AutomationKind[] values() {
    return catalog.toArray(AutomationKind[]::new);
  }

  /** @throws IllegalArgumentException when no action has this id, like an enum's {@code valueOf} */
  public static AutomationKind valueOf(String id) {
    for (AutomationKind action : catalog) {
      if (action.id().equals(id)) return action;
    }
    throw new IllegalArgumentException("Unknown action: " + id);
  }

  /**
   * The action for a stored id. An action that was deleted after it ran still has to show in the
   * history, so an unknown id becomes an inert placeholder instead of an error.
   */
  static AutomationKind lookup(String id) {
    try {
      return valueOf(id);
    } catch (IllegalArgumentException unknown) {
      return new AutomationKind(id, id.replace('_', ' ').toLowerCase(), "This action no longer exists.",
          id.toLowerCase().replace('_', '-'), id.toLowerCase().replace('_', '-'), false, false,
          false, false, false, ANY, false);
    }
  }

  /** The identifier, under the name an enum had. */
  public String name() { return id; }

  public String getTitle() { return title; }

  public String getDescription() { return description; }

  // Bean-style accessors for templates and expressions.
  public String getId() { return id; }
  public String getSkill() { return skill; }
  public String getBaseSkill() { return baseSkill; }
  public boolean isComments() { return comments; }
  public boolean isImproves() { return improves; }
  public boolean isBranchBased() { return branchBased; }
  public boolean isMerges() { return merges; }
  public boolean isVulnerability() { return vulnerability; }
  public boolean isBuiltin() { return builtin; }

  /**
   * The skill is one of the {@code cve-} skills: only the {@code vuln} scanner is used, so secrets
   * and misconfigurations (Dockerfiles, Helm charts, ...) are neither scanned nor fixed.
   */
  public boolean isCveOnly() { return skill.startsWith("cve-"); }

  public String title() { return title; }

  public String skill() { return skill; }

  public String baseSkill() { return baseSkill; }

  @Override
  public boolean equals(Object other) {
    return other instanceof AutomationKind kind && kind.id.equals(id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return id;
  }
}
