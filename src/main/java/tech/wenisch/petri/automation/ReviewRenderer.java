package tech.wenisch.petri.automation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders the structured review as one Markdown comment. Rendering happens here, not in the model,
 * so the layout is consistent and agent text is neutralised: HTML is escaped and {@code @mentions}
 * are defused so a review cannot ping people or inject markup.
 */
public final class ReviewRenderer {
  public static final String MARKER_PREFIX = "<!-- petri-code-review:";
  static final int MAX_COMMENT_CHARS = 60_000;
  public static final int MAX_COMMENT_CHARS_VALUE = MAX_COMMENT_CHARS;
  static final int MAX_FINDINGS = 20;

  private static final Pattern MENTION = Pattern.compile("@(?=[A-Za-z0-9_])");

  private ReviewRenderer() {}

  public static String render(String headSha, ReviewSubmission review) {
    return render(headSha, review, null);
  }

  /** @param previous the earlier review of the same pull request, when this is a re-review */
  public static String render(
      String headSha, ReviewSubmission review, ReviewSubmission.Previous previous) {
    boolean comparing = previous != null && review.comparison() != null;
    StringBuilder out = new StringBuilder();
    out.append(MARKER_PREFIX).append(headSha).append(" -->\n");
    out.append(comparing ? "## Petri code review (update)\n\n" : "## Petri code review\n\n");
    out.append("**Verdict:** ").append(review.verdict().label()).append("\n\n");
    out.append(clean(review.summary(), 4_000)).append("\n");
    if (comparing) comparison(out, headSha, review, previous);

    List<ReviewSubmission.Finding> findings = review.findings().stream()
        .sorted(Comparator.comparing(ReviewSubmission.Finding::severity))
        .limit(MAX_FINDINGS).toList();
    if (findings.isEmpty()) {
      out.append("\nNo findings.\n");
    } else {
      out.append("\n### Findings\n");
      for (ReviewSubmission.Finding finding : findings) {
        out.append("\n#### ").append(finding.severity().label()).append(": ")
            .append(clean(finding.title(), 200).replace("\n", " "));
        if (comparing) {
          out.append(finding.previousId() == null ? " (new)" : " (still open)");
        }
        out.append("\n");
        if (finding.path() != null) {
          out.append("`").append(finding.path().replace("`", ""));
          if (finding.line() != null) out.append(":").append(finding.line());
          out.append("`\n\n");
        } else {
          out.append("\n");
        }
        out.append(clean(finding.body(), 3_000)).append("\n");
        if (finding.suggestion() != null && !finding.suggestion().isBlank()) {
          String suggestion = finding.suggestion().strip();
          if (suggestion.length() > 2_000) suggestion = suggestion.substring(0, 2_000);
          String fence = suggestion.contains("```") ? "~~~~" : "```";
          out.append("\nSuggested change:\n\n").append(fence).append("\n")
              .append(suggestion).append("\n").append(fence).append("\n");
        }
      }
    }
    out.append("\n---\n_Automated review by Petri. Verify findings before acting on them._\n");
    if (out.length() > MAX_COMMENT_CHARS) {
      out.setLength(MAX_COMMENT_CHARS);
      out.append("\n\n_Review truncated._\n");
    }
    return out.toString();
  }

  /**
   * Better or worse than before, judged by severity-weighted open findings. Computed here so the
   * statement in the comment is reproducible from the numbers shown next to it.
   */
  public static ReviewSubmission.Trend trend(
      ReviewSubmission previous, ReviewSubmission current) {
    int before = score(previous.findings());
    int after = score(current.findings());
    if (after < before) return ReviewSubmission.Trend.IMPROVED;
    if (after > before) return ReviewSubmission.Trend.WORSE;
    boolean changed = current.findings().stream().anyMatch(finding -> finding.previousId() == null)
        || resolvedIds(previous, current).size() > 0;
    return changed ? ReviewSubmission.Trend.MIXED : ReviewSubmission.Trend.UNCHANGED;
  }

  static List<Integer> resolvedIds(ReviewSubmission previous, ReviewSubmission current) {
    Set<Integer> continued = new HashSet<>();
    current.findings().forEach(finding -> {
      if (finding.previousId() != null) continued.add(finding.previousId());
    });
    List<Integer> resolved = new ArrayList<>();
    for (int id = 1; id <= previous.findings().size(); id++) {
      if (!continued.contains(id)) resolved.add(id);
    }
    return resolved;
  }

  private static int score(List<ReviewSubmission.Finding> findings) {
    return findings.stream().mapToInt(finding -> finding.severity().weight()).sum();
  }

  private static void comparison(
      StringBuilder out, String headSha, ReviewSubmission review, ReviewSubmission.Previous previous) {
    ReviewSubmission before = previous.review();
    ReviewSubmission.Trend trend = trend(before, review);
    List<Integer> resolved = resolvedIds(before, review);
    long introduced = review.findings().stream().filter(finding -> finding.previousId() == null).count();
    long remaining = review.findings().size() - introduced;

    out.append("\n### Since the previous review\n\n");
    out.append("Compared with `").append(shortSha(previous.headSha())).append("`, commit `")
        .append(shortSha(headSha)).append("` is **").append(trend.label().toLowerCase()).append("**: ")
        .append(resolved.size()).append(" resolved, ").append(remaining).append(" still open, ")
        .append(introduced).append(" new.\n");
    String summary = clean(review.comparison().summary(), 2_000);
    if (!summary.isBlank()) out.append("\n").append(summary).append("\n");
    if (!resolved.isEmpty()) {
      out.append("\nResolved:\n");
      for (int id : resolved) {
        ReviewSubmission.Finding finding = before.findings().get(id - 1);
        out.append("- ").append(clean(finding.title(), 200).replace("\n", " "));
        if (finding.path() != null) out.append(" (`").append(finding.path().replace("`", "")).append("`)");
        out.append("\n");
      }
    }
  }

  private static String shortSha(String sha) {
    return sha == null ? "?" : sha.substring(0, Math.min(8, sha.length()));
  }

  static String clean(String value, int max) {
    String text = value == null ? "" : value.strip();
    if (text.length() > max) text = text.substring(0, max - 3) + "...";
    text = text.replace("&", "&amp;").replace("<", "&lt;");
    return MENTION.matcher(text).replaceAll("@​");
  }
}
