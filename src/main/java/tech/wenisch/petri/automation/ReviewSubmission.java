package tech.wenisch.petri.automation;

import java.util.List;

/**
 * Structured review produced by the agent through {@code submit_review}.
 *
 * @param comparison present only on a re-review, after new commits were pushed to the pull request
 */
public record ReviewSubmission(
    String summary, Verdict verdict, List<Finding> findings, Comparison comparison) {

  public ReviewSubmission(String summary, Verdict verdict, List<Finding> findings) {
    this(summary, verdict, findings, null);
  }

  public enum Verdict {
    APPROVE("Looks good"),
    COMMENT("Comments"),
    REQUEST_CHANGES("Changes requested");

    private final String label;

    Verdict(String label) { this.label = label; }

    public String label() { return label; }
  }

  public enum Severity {
    BLOCKER("Blocker", 8),
    MAJOR("Major", 4),
    MINOR("Minor", 1),
    NIT("Nit", 0);

    private final String label;
    private final int weight;

    Severity(String label, int weight) {
      this.label = label;
      this.weight = weight;
    }

    public String label() { return label; }

    public int weight() { return weight; }
  }

  /** How the pull request changed since the previous review. Derived by Petri, not by the model. */
  public enum Trend {
    IMPROVED("Improved"),
    UNCHANGED("Unchanged"),
    MIXED("Mixed"),
    WORSE("Worse");

    private final String label;

    Trend(String label) { this.label = label; }

    public String label() { return label; }
  }

  /**
   * @param previousId number of the finding in the previous review that this one continues, or
   *     null for a problem introduced since then
   */
  public record Finding(
      String path, Integer line, Severity severity, String title, String body, String suggestion,
      Integer previousId) {

    public Finding(
        String path, Integer line, Severity severity, String title, String body, String suggestion) {
      this(path, line, severity, title, body, suggestion, null);
    }
  }

  /**
   * @param summary what the new commits changed about the previous findings
   * @param resolved numbers of previous findings that are fixed; every other previous finding must be
   *     continued by a finding that names it in {@code previousId}
   */
  public record Comparison(String summary, List<Integer> resolved) {}

  /** The review a pull request received for an earlier head commit. */
  public record Previous(String headSha, ReviewSubmission review) {}
}
