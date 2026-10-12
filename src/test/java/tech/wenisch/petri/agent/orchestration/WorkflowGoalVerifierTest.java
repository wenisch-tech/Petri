package tech.wenisch.petri.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.agent.run.TestRunStatus;
import tech.wenisch.petri.agent.run.TestRunView;
import tools.jackson.databind.ObjectMapper;

class WorkflowGoalVerifierTest {
  private final WorkflowGoalVerifier verifier = new WorkflowGoalVerifier(new ObjectMapper());

  private static final String PLAN = """
      {"kind":"PLAN","title":"t","objective":"o","steps":["s"],"acceptanceCriteria":[
        {"id":"AC1","text":"login works"},{"id":"AC2","text":"docs updated"},
        {"id":"AC3","text":"no regressions"}]}
      """;
  private static final String DIFF = """
      {"paths":["src/Login.java"],"finalHashes":{"src/Login.java":"abc123"},"tests":[]}
      """;

  private static TestRunView test(UUID id, TestRunStatus status) {
    return new TestRunView(id, UUID.randomUUID(), UUID.randomUUID(), null, "mvn test", status,
        null, null, false, null, null, null, null, null, null, null, null, null);
  }

  private static String review(String coverage) {
    return "{\"decision\":\"APPROVED\",\"summary\":\"ok\",\"findings\":[],\"planCoverage\":"
        + coverage + "}";
  }

  private GoalStatus status(List<GoalVerificationView> goals, String id) {
    return goals.stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow().status();
  }

  @Test
  void everyCriterionIsUnverifiedBeforeAnyReview() {
    var goals = verifier.verify(PLAN, null, false, null, List.of());

    assertThat(goals).extracting(GoalVerificationView::id).containsExactly("AC1", "AC2", "AC3");
    assertThat(goals).extracting(GoalVerificationView::status)
        .containsOnly(GoalStatus.UNVERIFIED);
    assertThat(WorkflowGoalVerifier.allVerified(goals)).isFalse();
  }

  @Test
  void aMetClaimIsVerifiedOnlyWhenACitedTestPassedOrACitedFileChanged() {
    UUID passed = UUID.randomUUID();
    String coverage = """
        [{"criterionId":"AC1","status":"MET","evidence":[{"type":"TEST","ref":"%s"}]},
         {"criterionId":"AC2","status":"MET","evidence":[{"type":"FILE","ref":"src/Login.java"}]},
         {"criterionId":"AC3","status":"MET","evidence":[]}]
        """.formatted(passed);

    var goals = verifier.verify(PLAN, review(coverage), false, DIFF,
        List.of(test(passed, TestRunStatus.PASSED)));

    assertThat(status(goals, "AC1")).isEqualTo(GoalStatus.VERIFIED);
    assertThat(status(goals, "AC2")).isEqualTo(GoalStatus.VERIFIED);
    assertThat(status(goals, "AC3")).isEqualTo(GoalStatus.CLAIMED);
    assertThat(WorkflowGoalVerifier.unverifiedIds(goals)).containsExactly("AC3");
  }

  @Test
  void citationsTheServerCannotConfirmNeverVerify() {
    UUID failed = UUID.randomUUID();
    String coverage = """
        [{"criterionId":"AC1","status":"MET","evidence":[{"type":"TEST","ref":"%s"}]},
         {"criterionId":"AC2","status":"MET","evidence":[{"type":"TEST","ref":"%s"}]},
         {"criterionId":"AC3","status":"MET","evidence":[{"type":"FILE","ref":"src/Other.java"}]}]
        """.formatted(failed, UUID.randomUUID());

    var goals = verifier.verify(PLAN, review(coverage), false, DIFF,
        List.of(test(failed, TestRunStatus.FAILED)));

    assertThat(status(goals, "AC1")).isEqualTo(GoalStatus.NOT_MET);
    assertThat(status(goals, "AC2")).isEqualTo(GoalStatus.CLAIMED);
    assertThat(status(goals, "AC3")).isEqualTo(GoalStatus.CLAIMED);
    assertThat(goals.get(0).reasons()).anyMatch(reason -> reason.contains("did not pass"));
  }

  @Test
  void aFileThatNoLongerMatchesWhatWasAppliedDoesNotCount() {
    String diff = "{\"paths\":[\"src/Login.java\"],"
        + "\"finalHashes\":{\"src/Login.java\":\"EXTERNAL_CONFLICT\"}}";
    String coverage = "[{\"criterionId\":\"AC1\",\"status\":\"MET\","
        + "\"evidence\":[{\"type\":\"FILE\",\"ref\":\"src/Login.java\"}]}]";

    var goals = verifier.verify(PLAN, review(coverage), false, diff, List.of());

    assertThat(status(goals, "AC1")).isEqualTo(GoalStatus.CLAIMED);
  }

  @Test
  void partialAndNotMetClaimsAreReportedAsStated() {
    String coverage = """
        [{"criterionId":"AC1","status":"PARTIAL","evidence":[]},
         {"criterionId":"AC2","status":"NOT_MET","evidence":[]}]
        """;

    var goals = verifier.verify(PLAN, review(coverage), false, DIFF, List.of());

    assertThat(status(goals, "AC1")).isEqualTo(GoalStatus.PARTIAL);
    assertThat(status(goals, "AC2")).isEqualTo(GoalStatus.NOT_MET);
    assertThat(status(goals, "AC3")).isEqualTo(GoalStatus.UNVERIFIED);
  }

  @Test
  void aStaleReviewDoesNotSpeakForNewChanges() {
    UUID passed = UUID.randomUUID();
    String coverage = "[{\"criterionId\":\"AC1\",\"status\":\"MET\","
        + "\"evidence\":[{\"type\":\"TEST\",\"ref\":\"" + passed + "\"}]}]";

    var goals = verifier.verify(PLAN, review(coverage), true, DIFF,
        List.of(test(passed, TestRunStatus.PASSED)));

    assertThat(goals).extracting(GoalVerificationView::status).containsOnly(GoalStatus.UNVERIFIED);
  }

  @Test
  void legacyStringCriteriaGetPositionalIds() {
    String legacy = "{\"acceptanceCriteria\":[\"first\",\"second\"]}";

    assertThat(WorkflowGoalVerifier.goals(new ObjectMapper().readTree(legacy)))
        .extracting(WorkflowGoalVerifier.Goal::id).containsExactly("AC1", "AC2");
  }

  @Test
  void aPlanWithoutCriteriaNeverBlocks() {
    assertThat(WorkflowGoalVerifier.allVerified(List.of())).isTrue();
  }
}
