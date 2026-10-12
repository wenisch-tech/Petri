package tech.wenisch.petri.automation;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Accepts the agent's structured review for one run and stores the rendered comment. */
@Service
public class AutomationReviewService {
  private final AutomationRunService runs;
  private final ObjectMapper json;

  public AutomationReviewService(AutomationRunService runs, ObjectMapper json) {
    this.runs = runs;
    this.json = json;
  }

  /** The review the pull request got for an earlier commit, if there is one. */
  public Optional<ReviewSubmission.Previous> previous(UUID automationRunId) {
    return runs.previousReview(automationRunId).flatMap(run -> {
      try {
        return Optional.of(new ReviewSubmission.Previous(
            run.getHeadSha(), json.readValue(run.getReviewJson(), ReviewSubmission.class)));
      } catch (JacksonException exception) {
        return Optional.empty();
      }
    });
  }

  /** Validates the review against the pull request; returns the rendered comment. */
  public String submit(UUID automationRunId, ReviewSubmission review) {
    AutomationRun run = runs.require(automationRunId);
    if (run.getReviewMarkdown() != null) {
      throw new IllegalStateException("A review was already submitted for this pull request");
    }
    Set<String> changed = new HashSet<>(Arrays.asList(
        run.getChangedFiles() == null ? new String[0] : run.getChangedFiles().split("\n")));
    for (ReviewSubmission.Finding finding : review.findings()) {
      if (finding.path() != null && !changed.contains(finding.path())) {
        throw new IllegalArgumentException(
            "Finding path is not a changed file of this pull request: " + finding.path());
      }
    }
    ReviewSubmission.Previous previous = previous(automationRunId).orElse(null);
    ReviewSubmission checked = previous == null ? withoutComparison(review)
        : validateComparison(review, previous.review());
    String markdown = ReviewRenderer.render(run.getHeadSha(), checked, previous);
    String stored;
    try {
      stored = json.writeValueAsString(checked);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Review could not be stored", exception);
    }
    runs.review(automationRunId, markdown, stored);
    return markdown;
  }

  /**
   * Stores the agent's final text as the review when it answered without calling
   * {@code submit_review} (for example after the loop guard withdrew its tools). It is rendered
   * through the same escaping as a structured review, as a comment without findings.
   */
  public String submitUnstructured(UUID automationRunId, String text) {
    AutomationRun run = runs.require(automationRunId);
    if (run.getReviewMarkdown() != null) {
      throw new IllegalStateException("A review was already submitted for this pull request");
    }
    String summary = text == null ? "" : text.strip();
    if (summary.isEmpty()) throw new IllegalArgumentException("The agent returned no text to use");
    ReviewSubmission review = new ReviewSubmission(
        summary, ReviewSubmission.Verdict.COMMENT, List.of(), null);
    String markdown = ReviewRenderer.render(run.getHeadSha(), review, null);
    String stored;
    try {
      stored = json.writeValueAsString(review);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Review could not be stored", exception);
    }
    runs.review(automationRunId, markdown, stored);
    return markdown;
  }

  private static ReviewSubmission withoutComparison(ReviewSubmission review) {
    List<ReviewSubmission.Finding> findings = review.findings().stream()
        .map(finding -> new ReviewSubmission.Finding(finding.path(), finding.line(),
            finding.severity(), finding.title(), finding.body(), finding.suggestion(), null))
        .toList();
    return new ReviewSubmission(review.summary(), review.verdict(), findings, null);
  }

  /**
   * Every finding of the previous review must be accounted for, exactly once: either fixed
   * ({@code comparison.resolved}) or continued by a current finding ({@code previousId}).
   */
  private static ReviewSubmission validateComparison(
      ReviewSubmission review, ReviewSubmission previous) {
    int count = previous.findings().size();
    if (count == 0) {
      // Nothing to compare finding by finding; the summary still explains the new commits.
      return new ReviewSubmission(review.summary(), review.verdict(), review.findings().stream()
          .map(finding -> new ReviewSubmission.Finding(finding.path(), finding.line(),
              finding.severity(), finding.title(), finding.body(), finding.suggestion(), null))
          .toList(), review.comparison() == null
              ? new ReviewSubmission.Comparison("", List.of()) : review.comparison());
    }
    if (review.comparison() == null) {
      throw new IllegalArgumentException(
          "This is a re-review: include comparison with the previous findings (resolved ids)");
    }
    Set<Integer> accounted = new HashSet<>();
    for (Integer id : review.comparison().resolved()) {
      requireKnown(id, count);
      if (!accounted.add(id)) throw new IllegalArgumentException("Previous finding " + id + " listed twice");
    }
    for (ReviewSubmission.Finding finding : review.findings()) {
      Integer id = finding.previousId();
      if (id == null) continue;
      requireKnown(id, count);
      if (!accounted.add(id)) {
        throw new IllegalArgumentException("Previous finding " + id + " is accounted for twice");
      }
    }
    for (int id = 1; id <= count; id++) {
      if (!accounted.contains(id)) {
        throw new IllegalArgumentException("Previous finding " + id
            + " is neither in comparison.resolved nor continued by a finding with previousId");
      }
    }
    return review;
  }

  private static void requireKnown(int id, int count) {
    if (id < 1 || id > count) {
      throw new IllegalArgumentException("Unknown previous finding " + id + "; valid ids are 1.." + count);
    }
  }
}
