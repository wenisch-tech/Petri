package tech.wenisch.petri.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.automation.AutomationReviewService;
import tech.wenisch.petri.automation.ReviewRenderer;
import tech.wenisch.petri.automation.ReviewSubmission;

/**
 * Hands the finished review to Petri. The tool never contacts the provider; Petri renders the
 * comment itself and posts it according to the project's automation settings.
 */
@Component
public class SubmitReviewTool implements AgentTool {
  private static final int MAX_FINDINGS = 20;

  private static final Map<String, Object> FINDING = Map.of(
      "type", "object",
      "properties", Map.of(
          "path", ToolSchemas.nullableString("Repository path of a changed file, or null for a general finding."),
          "line", ToolSchemas.nullableInteger("Line number in the new version of the file, or null.", 1, 10_000_000),
          "severity", Map.of("type", "string", "enum", List.of("BLOCKER", "MAJOR", "MINOR", "NIT")),
          "title", ToolSchemas.string("Short title of the finding."),
          "body", ToolSchemas.string("What is wrong, why it matters and how to fix it."),
          "suggestion", ToolSchemas.nullableString("Optional replacement code, or null."),
          "previousId", ToolSchemas.nullableInteger(
              "Re-review only: number of the previous finding this one continues, else null.",
              1, 1000)),
      "required", List.of(
          "path", "line", "severity", "title", "body", "suggestion", "previousId"),
      "additionalProperties", false);

  private static final Map<String, Object> COMPARISON = Map.of(
      "type", List.of("object", "null"),
      "description", "Re-review only, else null. Accounts for the previous review's findings.",
      "properties", Map.of(
          "summary", ToolSchemas.string(
              "What the new commits changed: which problems were fixed, which remain, what is new."),
          "resolved", Map.of("type", "array", "items", Map.of("type", "integer"),
              "description", "Numbers of previous findings that are fixed in the new head.")),
      "required", List.of("summary", "resolved"),
      "additionalProperties", false);

  private static final ToolDefinition DEFINITION = new ToolDefinition(
      "submit_review",
      "Submit the final pull request review. Call exactly once, after inspecting the change.",
      ToolSchemas.object(
          Map.of(
              "summary", ToolSchemas.string("Overall assessment in a few sentences."),
              "verdict", Map.of("type", "string", "enum", List.of("APPROVE", "COMMENT", "REQUEST_CHANGES")),
              "findings", Map.of("type", "array", "maxItems", MAX_FINDINGS, "items", FINDING),
              "comparison", COMPARISON),
          List.of("summary", "verdict", "findings", "comparison")));

  private final AutomationReviewService reviews;

  public SubmitReviewTool(AutomationReviewService reviews) {
    this.reviews = reviews;
  }

  @Override
  public ToolDefinition definition() { return DEFINITION; }

  @Override
  public Object execute(ToolExecutionContext context, Map<String, Object> input) {
    if (context.automationRunId() == null) {
      throw new IllegalStateException("submit_review is only available to automation runs");
    }
    String summary = ToolInputs.requiredString(input, "summary", 12_000);
    ReviewSubmission.Verdict verdict = enumValue(
        ReviewSubmission.Verdict.class, ToolInputs.requiredString(input, "verdict", 32), "verdict");
    if (!(input.get("findings") instanceof List<?> raw)) {
      throw new IllegalArgumentException("findings must be an array");
    }
    if (raw.size() > MAX_FINDINGS) {
      throw new IllegalArgumentException("At most " + MAX_FINDINGS + " findings are allowed; merge related ones");
    }
    List<ReviewSubmission.Finding> findings = new ArrayList<>();
    for (Object item : raw) {
      if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException("Each finding must be an object");
      @SuppressWarnings("unchecked")
      Map<String, Object> finding = (Map<String, Object>) map;
      String path = ToolInputs.string(finding, "path", null, 500);
      Integer line = finding.get("line") == null ? null
          : ToolInputs.integer(finding, "line", 1, 1, 10_000_000);
      findings.add(new ReviewSubmission.Finding(
          path == null || path.isBlank() ? null : path.strip(),
          line,
          enumValue(ReviewSubmission.Severity.class,
              ToolInputs.requiredString(finding, "severity", 16), "severity"),
          ToolInputs.requiredString(finding, "title", 300),
          ToolInputs.requiredString(finding, "body", 6_000),
          ToolInputs.string(finding, "suggestion", null, 4_000),
          finding.get("previousId") == null ? null
              : ToolInputs.integer(finding, "previousId", 1, 1, 1000)));
    }
    String markdown = reviews.submit(context.automationRunId(),
        new ReviewSubmission(summary, verdict, List.copyOf(findings), comparison(input.get("comparison"))));
    return Map.of("status", "submitted", "findings", findings.size(),
        "characters", Math.min(markdown.length(), ReviewRenderer.MAX_COMMENT_CHARS_VALUE));
  }

  private static ReviewSubmission.Comparison comparison(Object raw) {
    if (raw == null) return null;
    if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException("comparison must be an object");
    @SuppressWarnings("unchecked")
    Map<String, Object> comparison = (Map<String, Object>) map;
    List<Integer> resolved = new ArrayList<>();
    Object ids = comparison.get("resolved");
    if (ids != null) {
      if (!(ids instanceof List<?> list)) throw new IllegalArgumentException("resolved must be an array");
      for (Object id : list) {
        if (!(id instanceof Number number) || number.doubleValue() != number.intValue()) {
          throw new IllegalArgumentException("resolved must contain finding numbers");
        }
        resolved.add(number.intValue());
      }
    }
    return new ReviewSubmission.Comparison(
        ToolInputs.string(comparison, "summary", "", 4_000), List.copyOf(resolved));
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String name) {
    try {
      return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(name + " is not one of the allowed values");
    }
  }
}
