package tech.wenisch.petri.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class AutomationReviewServiceTest {
  private final ObjectMapper json = new ObjectMapper();
  private final AutomationRunService runs = mock(AutomationRunService.class);
  private final AutomationReviewService service = new AutomationReviewService(runs, json);

  private AutomationRun run(String sha) {
    AutomationRun run = new AutomationRun(UUID.randomUUID(), UUID.randomUUID(),
        AutomationKind.CODE_REVIEW, AutomationTrigger.PULL_REQUEST, "7", sha, "T", null,
        AutomationPostMode.AUTO, Instant.now());
    run.workspace(UUID.randomUUID(), "src/A.java\nsrc/B.java", Instant.now());
    return run;
  }

  private static ReviewSubmission.Finding finding(
      String path, ReviewSubmission.Severity severity, String title, Integer previousId) {
    return new ReviewSubmission.Finding(path, 3, severity, title, "b", null, previousId);
  }

  private static ReviewSubmission review(ReviewSubmission.Comparison comparison,
      ReviewSubmission.Finding... findings) {
    return new ReviewSubmission("ok", ReviewSubmission.Verdict.COMMENT, List.of(findings), comparison);
  }

  /** A previous review with two findings, returned for the given run. */
  private void previousExists(AutomationRun current) throws Exception {
    AutomationRun previous = run("old111");
    ReviewSubmission earlier = review(null,
        finding("src/A.java", ReviewSubmission.Severity.MAJOR, "Race condition", null),
        finding("src/B.java", ReviewSubmission.Severity.MINOR, "Unused import", null));
    previous.review("old", json.writeValueAsString(earlier), Instant.now());
    when(runs.previousReview(current.getId())).thenReturn(Optional.of(previous));
  }

  @Test
  void storesTheRenderedCommentAndStructuredReviewForChangedFiles() {
    AutomationRun run = run("abc123");
    when(runs.require(run.getId())).thenReturn(run);
    when(runs.previousReview(run.getId())).thenReturn(Optional.empty());

    String markdown = service.submit(run.getId(), review(null,
        finding("src/A.java", ReviewSubmission.Severity.MINOR, "t", null)));

    assertThat(markdown).contains("petri-code-review:abc123").doesNotContain("Since the previous");
    ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
    verify(runs).review(org.mockito.ArgumentMatchers.eq(run.getId()),
        org.mockito.ArgumentMatchers.eq(markdown), stored.capture());
    assertThat(stored.getValue()).contains("\"severity\":\"MINOR\"");
  }

  @Test
  void rejectsFindingsOutsideTheChangeAndSecondSubmissions() {
    AutomationRun run = run("abc123");
    when(runs.require(run.getId())).thenReturn(run);
    when(runs.previousReview(run.getId())).thenReturn(Optional.empty());

    assertThatIllegalArgumentException().isThrownBy(() -> service.submit(run.getId(),
        review(null, finding("src/Other.java", ReviewSubmission.Severity.MINOR, "t", null))))
        .withMessageContaining("not a changed file");

    run.review("already", Instant.now());
    assertThatIllegalStateException().isThrownBy(() -> service.submit(run.getId(),
        review(null, finding("src/A.java", ReviewSubmission.Severity.MINOR, "t", null))))
        .withMessageContaining("already submitted");
  }

  @Test
  void aReReviewMustAccountForEveryPreviousFindingAndIsRenderedAsAComparison() throws Exception {
    AutomationRun run = run("new222");
    when(runs.require(run.getId())).thenReturn(run);
    previousExists(run);

    String markdown = service.submit(run.getId(), review(
        new ReviewSubmission.Comparison("Race fixed, import still unused.", List.of(1)),
        finding("src/B.java", ReviewSubmission.Severity.MINOR, "Unused import", 2),
        finding("src/A.java", ReviewSubmission.Severity.MAJOR, "Null deref", null)));

    assertThat(markdown).contains("Since the previous review", "`old111`", "`new222`",
        "1 resolved, 1 still open, 1 new", "Race condition", "(still open)", "(new)",
        "Race fixed, import still unused.");
  }

  @Test
  void aReReviewIsRejectedWhenAPreviousFindingIsMissingOrUnknownOrDuplicated() throws Exception {
    AutomationRun run = run("new222");
    when(runs.require(run.getId())).thenReturn(run);
    previousExists(run);

    assertThatIllegalArgumentException().isThrownBy(() -> service.submit(run.getId(), review(null)))
        .withMessageContaining("re-review");
    assertThatIllegalArgumentException().isThrownBy(() -> service.submit(run.getId(), review(
        new ReviewSubmission.Comparison("s", List.of(1)))))
        .withMessageContaining("Previous finding 2");
    assertThatIllegalArgumentException().isThrownBy(() -> service.submit(run.getId(), review(
        new ReviewSubmission.Comparison("s", List.of(1, 2, 9)))))
        .withMessageContaining("Unknown previous finding 9");
    assertThatIllegalArgumentException().isThrownBy(() -> service.submit(run.getId(), review(
        new ReviewSubmission.Comparison("s", List.of(1, 2)),
        finding("src/A.java", ReviewSubmission.Severity.MINOR, "t", 2))))
        .withMessageContaining("twice");
  }

  @Test
  void trendIsDerivedFromSeverityWeightedFindings() {
    ReviewSubmission before = review(null,
        finding("a", ReviewSubmission.Severity.MAJOR, "x", null),
        finding("a", ReviewSubmission.Severity.MINOR, "y", null));
    ReviewSubmission better = review(null, finding("a", ReviewSubmission.Severity.MINOR, "y", 2));
    ReviewSubmission worse = review(null,
        finding("a", ReviewSubmission.Severity.MAJOR, "x", 1),
        finding("a", ReviewSubmission.Severity.MINOR, "y", 2),
        finding("a", ReviewSubmission.Severity.BLOCKER, "z", null));
    ReviewSubmission same = review(null,
        finding("a", ReviewSubmission.Severity.MAJOR, "x", 1),
        finding("a", ReviewSubmission.Severity.MINOR, "y", 2));
    ReviewSubmission swapped = review(null,
        finding("a", ReviewSubmission.Severity.MAJOR, "w", null),
        finding("a", ReviewSubmission.Severity.MINOR, "y", 2));

    assertThat(ReviewRenderer.trend(before, better)).isEqualTo(ReviewSubmission.Trend.IMPROVED);
    assertThat(ReviewRenderer.trend(before, worse)).isEqualTo(ReviewSubmission.Trend.WORSE);
    assertThat(ReviewRenderer.trend(before, same)).isEqualTo(ReviewSubmission.Trend.UNCHANGED);
    assertThat(ReviewRenderer.trend(before, swapped)).isEqualTo(ReviewSubmission.Trend.MIXED);
  }

  @Test
  void anUnstructuredAnswerIsStoredAsACommentWithoutFindingsAndIsEscaped() {
    AutomationRun run = run("abc123");
    when(runs.require(run.getId())).thenReturn(run);

    String markdown = service.submitUnstructured(run.getId(),
        "  Looks fine overall. Ping @octocat <script>alert(1)</script>  ");

    assertThat(markdown).contains("petri-code-review:abc123", "Verdict:** Comments",
        "Looks fine overall.", "No findings.").doesNotContain("<script>", "@octocat");
    verify(runs).review(org.mockito.ArgumentMatchers.eq(run.getId()),
        org.mockito.ArgumentMatchers.eq(markdown), anyString());
    assertThatIllegalArgumentException().isThrownBy(
        () -> service.submitUnstructured(run.getId(), "   "));
  }
}

