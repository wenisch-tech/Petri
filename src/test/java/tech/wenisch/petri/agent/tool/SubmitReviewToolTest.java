package tech.wenisch.petri.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.wenisch.petri.automation.AutomationReviewService;
import tech.wenisch.petri.automation.ReviewSubmission;

class SubmitReviewToolTest {
  private final AutomationReviewService reviews = mock(AutomationReviewService.class);
  private final SubmitReviewTool tool = new SubmitReviewTool(reviews);
  private final UUID automationRunId = UUID.randomUUID();

  private ToolExecutionContext context() {
    return new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), null, automationRunId);
  }

  @Test
  void parsesAStructuredReviewAndHandsItToTheReviewService() {
    when(reviews.submit(any(), any())).thenReturn("rendered");

    Object result = tool.execute(context(), Map.of(
        "summary", "Solid change.",
        "verdict", "request_changes",
        "findings", List.of(Map.of(
            "path", "src/A.java", "line", 12, "severity", "major",
            "title", "NPE", "body", "May be null.", "suggestion", "x != null"))));

    ArgumentCaptor<ReviewSubmission> captured = ArgumentCaptor.forClass(ReviewSubmission.class);
    verify(reviews).submit(org.mockito.ArgumentMatchers.eq(automationRunId), captured.capture());
    assertThat(captured.getValue().verdict()).isEqualTo(ReviewSubmission.Verdict.REQUEST_CHANGES);
    assertThat(captured.getValue().findings()).singleElement().satisfies(finding -> {
      assertThat(finding.path()).isEqualTo("src/A.java");
      assertThat(finding.line()).isEqualTo(12);
      assertThat(finding.severity()).isEqualTo(ReviewSubmission.Severity.MAJOR);
    });
    assertThat(result).isInstanceOf(Map.class);
  }

  @Test
  void rejectsInvalidInputAndUseOutsideAutomations() {
    assertThatThrownBy(() -> tool.execute(context(), Map.of(
        "summary", "s", "verdict", "MAYBE", "findings", List.of())))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("verdict");
    assertThatThrownBy(() -> tool.execute(context(), Map.of(
        "summary", "s", "verdict", "COMMENT", "findings", "none")))
        .hasMessageContaining("findings must be an array");
    assertThatThrownBy(() -> tool.execute(new ToolExecutionContext(UUID.randomUUID()), Map.of(
        "summary", "s", "verdict", "COMMENT", "findings", List.of())))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void parsesComparisonAndPreviousFindingLinks() {
    when(reviews.submit(any(), any())).thenReturn("rendered");

    tool.execute(context(), Map.of(
        "summary", "Better.",
        "verdict", "COMMENT",
        "findings", List.of(Map.of(
            "path", "src/A.java", "line", 3, "severity", "minor", "title", "Still open",
            "body", "Still here.", "previousId", 2)),
        "comparison", Map.of("summary", "Race fixed.", "resolved", List.of(1))));

    ArgumentCaptor<ReviewSubmission> captured = ArgumentCaptor.forClass(ReviewSubmission.class);
    verify(reviews).submit(org.mockito.ArgumentMatchers.eq(automationRunId), captured.capture());
    assertThat(captured.getValue().comparison().resolved()).containsExactly(1);
    assertThat(captured.getValue().comparison().summary()).isEqualTo("Race fixed.");
    assertThat(captured.getValue().findings().get(0).previousId()).isEqualTo(2);
  }
}
