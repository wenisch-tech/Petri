package tech.wenisch.petri.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewRendererTest {
  @Test
  void rendersMarkerVerdictAndFindingsBySeverity() {
    String markdown = ReviewRenderer.render("abc123", new ReviewSubmission(
        "Adds a cache.", ReviewSubmission.Verdict.REQUEST_CHANGES, List.of(
            new ReviewSubmission.Finding("src/A.java", 12, ReviewSubmission.Severity.NIT,
                "Naming", "Prefer a clearer name.", null),
            new ReviewSubmission.Finding("src/B.java", 30, ReviewSubmission.Severity.BLOCKER,
                "Race", "Two threads can both write.", "lock.lock();"))));

    assertThat(markdown).startsWith("<!-- petri-code-review:abc123 -->");
    assertThat(markdown).contains("**Verdict:** Changes requested", "Adds a cache.",
        "#### Blocker: Race", "`src/B.java:30`", "Suggested change:", "lock.lock();");
    assertThat(markdown.indexOf("Blocker: Race")).isLessThan(markdown.indexOf("Nit: Naming"));
  }

  @Test
  void aFirstReviewHasNoComparisonSection() {
    String markdown = ReviewRenderer.render("abc", new ReviewSubmission(
        "s", ReviewSubmission.Verdict.APPROVE, List.of()));

    assertThat(markdown).doesNotContain("Since the previous review", "(update)");
  }

  @Test
  void neutralisesHtmlMentionsAndFenceBreakouts() {
    String markdown = ReviewRenderer.render("abc", new ReviewSubmission(
        "Ping @octocat <script>alert(1)</script>", ReviewSubmission.Verdict.COMMENT, List.of(
            new ReviewSubmission.Finding(null, null, ReviewSubmission.Severity.MINOR,
                "Title <b>x</b>", "Body", "```\nescape\n```"))));

    assertThat(markdown).doesNotContain("<script>", "<b>", "@octocat");
    assertThat(markdown).contains("&lt;script>", "@​octocat", "~~~~");
  }

  @Test
  void reportsNoFindingsAndCapsFindingCountAndLength() {
    assertThat(ReviewRenderer.render("a", new ReviewSubmission(
        "Fine.", ReviewSubmission.Verdict.APPROVE, List.of()))).contains("No findings.");

    List<ReviewSubmission.Finding> many = java.util.stream.IntStream.range(0, 40)
        .mapToObj(index -> new ReviewSubmission.Finding("f", index + 1,
            ReviewSubmission.Severity.MINOR, "t" + index, "x".repeat(3_000), null)).toList();
    String markdown = ReviewRenderer.render("a", new ReviewSubmission(
        "s", ReviewSubmission.Verdict.COMMENT, many));

    assertThat(markdown.length()).isLessThanOrEqualTo(ReviewRenderer.MAX_COMMENT_CHARS + 40);
    assertThat(markdown).doesNotContain("t25");
  }
}
