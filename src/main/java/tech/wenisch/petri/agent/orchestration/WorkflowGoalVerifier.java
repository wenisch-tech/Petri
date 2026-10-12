package tech.wenisch.petri.agent.orchestration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.run.TestRunStatus;
import tech.wenisch.petri.agent.run.TestRunView;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Decides, per acceptance criterion of the plan, whether the reviewer's coverage claim is backed by
 * evidence the server can confirm. Model output is never trusted on its own: a "MET" counts as
 * VERIFIED only when a cited test run really passed or a cited file was really changed.
 */
@Component
class WorkflowGoalVerifier {
  private static final Set<String> FAILED_TESTS = Set.of(
      TestRunStatus.FAILED.name(), TestRunStatus.ERROR.name(), TestRunStatus.TIMED_OUT.name());

  private final ObjectMapper mapper;

  WorkflowGoalVerifier(ObjectMapper mapper) { this.mapper = mapper; }

  /** A criterion with a stable id. Legacy plans hold bare strings, which are numbered by position. */
  record Goal(String id, String text) {}

  /**
   * The criteria of a plan. Accepts strings and {"id","text"} objects and never throws, so a
   * malformed legacy plan simply yields fewer goals.
   */
  static List<Goal> goals(JsonNode plan) {
    List<Goal> result = new ArrayList<>();
    if (plan == null) return result;
    JsonNode criteria = plan.path("acceptanceCriteria");
    if (!criteria.isArray()) return result;
    int position = 0;
    for (JsonNode item : criteria) {
      position++;
      String id = "AC" + position;
      String text;
      if (item.isTextual()) {
        text = item.asText();
      } else if (item.isObject()) {
        String candidate = item.path("id").asText("").strip();
        if (!candidate.isEmpty()) id = candidate;
        text = item.path("text").asText("");
      } else {
        text = item.toString();
      }
      result.add(new Goal(id, text.strip()));
    }
    return result;
  }

  /**
   * @param planJson the current PLAN artifact
   * @param reviewJson the latest REVIEW artifact, or null before any review
   * @param reviewStale true when implementation evidence changed after that review
   * @param taskDiffJson the latest TASK_DIFF artifact, or null
   * @param tests the workflow's test runs as they are now
   */
  List<GoalVerificationView> verify(
      String planJson, String reviewJson, boolean reviewStale, String taskDiffJson,
      List<TestRunView> tests) {
    JsonNode plan = read(planJson);
    JsonNode review = reviewStale ? null : read(reviewJson);
    JsonNode diff = read(taskDiffJson);
    Map<String, TestRunView> testsById = new HashMap<>();
    for (TestRunView test : tests) testsById.put(test.id().toString(), test);
    Map<String, String> hashes = new LinkedHashMap<>();
    if (diff != null) {
      diff.path("finalHashes").properties().forEach(entry -> hashes.put(entry.getKey(), entry.getValue().asText()));
    }
    Map<String, JsonNode> coverage = new HashMap<>();
    if (review != null) {
      for (JsonNode entry : review.path("planCoverage")) {
        coverage.putIfAbsent(entry.path("criterionId").asText(), entry);
      }
    }
    List<GoalVerificationView> result = new ArrayList<>();
    for (Goal goal : goals(plan)) {
      JsonNode entry = coverage.get(goal.id());
      if (entry == null) {
        result.add(new GoalVerificationView(goal.id(), goal.text(), GoalStatus.UNVERIFIED, null,
            List.of(reviewStale ? "Waiting for a new review after the latest changes"
                : "Not covered by a review yet"), List.of()));
        continue;
      }
      result.add(assess(goal, entry, testsById, hashes));
    }
    return List.copyOf(result);
  }

  private GoalVerificationView assess(
      Goal goal, JsonNode entry, Map<String, TestRunView> tests, Map<String, String> hashes) {
    String claimed = entry.path("status").asText("");
    String note = entry.path("note").asText(null);
    List<String> reasons = new ArrayList<>();
    List<GoalVerificationView.Evidence> evidence = new ArrayList<>();
    boolean confirmed = false;
    boolean contradicted = false;
    for (JsonNode cited : entry.path("evidence")) {
      String type = cited.path("type").asText("");
      String ref = cited.path("ref").asText("");
      if ("TEST".equals(type)) {
        TestRunView test = tests.get(ref);
        if (test == null) {
          evidence.add(new GoalVerificationView.Evidence(type, ref, ref, "UNRESOLVED"));
          reasons.add("Cited test run does not exist: " + ref);
        } else if (test.status() == TestRunStatus.PASSED) {
          evidence.add(new GoalVerificationView.Evidence(type, ref, test.command(), "PASSED"));
          confirmed = true;
        } else {
          evidence.add(new GoalVerificationView.Evidence(
              type, ref, test.command(), test.status().name()));
          if (FAILED_TESTS.contains(test.status().name())) {
            contradicted = true;
            reasons.add("Cited test did not pass: " + test.command());
          } else {
            reasons.add("Cited test has not finished: " + test.command());
          }
        }
      } else if ("FILE".equals(type)) {
        String hash = hashes.get(ref);
        if (hash == null) {
          evidence.add(new GoalVerificationView.Evidence(type, ref, ref, "UNRESOLVED"));
          reasons.add("Cited file was not changed by this workflow: " + ref);
        } else if ("EXTERNAL_CONFLICT".equals(hash)) {
          evidence.add(new GoalVerificationView.Evidence(type, ref, ref, "FAILED"));
          reasons.add("Cited file no longer matches what was applied: " + ref);
        } else {
          evidence.add(new GoalVerificationView.Evidence(type, ref, ref, "APPLIED"));
          confirmed = true;
        }
      } else {
        reasons.add("Unsupported evidence type: " + type);
      }
    }
    GoalStatus status;
    if ("NOT_MET".equals(claimed) || (contradicted && !confirmed)) {
      status = GoalStatus.NOT_MET;
    } else if ("PARTIAL".equals(claimed)) {
      status = GoalStatus.PARTIAL;
    } else if ("MET".equals(claimed) && confirmed) {
      status = GoalStatus.VERIFIED;
    } else if ("MET".equals(claimed)) {
      status = GoalStatus.CLAIMED;
      if (reasons.isEmpty()) reasons.add("The reviewer cited no evidence");
    } else {
      status = GoalStatus.UNVERIFIED;
    }
    return new GoalVerificationView(
        goal.id(), goal.text(), status, note, List.copyOf(reasons), List.copyOf(evidence));
  }

  /** True when every goal is evidence-backed; an empty goal list never blocks. */
  static boolean allVerified(List<GoalVerificationView> goals) {
    return goals.stream().allMatch(value -> value.status() == GoalStatus.VERIFIED);
  }

  /** Goal ids that are not verified, for messages. */
  static List<String> unverifiedIds(List<GoalVerificationView> goals) {
    return goals.stream().filter(value -> value.status() != GoalStatus.VERIFIED)
        .map(GoalVerificationView::id).toList();
  }

  private JsonNode read(String json) {
    if (json == null || json.isBlank()) return null;
    try {
      return mapper.readTree(json);
    } catch (JacksonException exception) {
      return null;
    }
  }
}
