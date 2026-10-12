package tech.wenisch.petri.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.agent.run.TestBuildSystem;
import tech.wenisch.petri.agent.run.TestRunStatus;
import tech.wenisch.petri.agent.run.TestRunView;

class WorkflowGateRoutingTest {
  private static final Instant START = Instant.parse("2026-10-12T18:00:00Z");

  private static TestRunView run(String command, TestRunStatus status, int minute) {
    Instant at = START.plusSeconds(minute * 60L);
    return new TestRunView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        TestBuildSystem.MAVEN, command, status, status == TestRunStatus.PASSED ? 0 : 1, "", false,
        at, at, at, at, null, null, null, null, null);
  }

  @Test
  void aFailureFollowedByAPassingRunOfTheSameCommandIsHistory() {
    assertThat(WorkflowEvidenceService.currentFailure(List.of(
        run("mvn test", TestRunStatus.FAILED, 1),
        run("mvn test", TestRunStatus.PASSED, 5)))).isFalse();
  }

  @Test
  void aLatestFailureStillCountsEvenIfAnEarlierRunPassed() {
    assertThat(WorkflowEvidenceService.currentFailure(List.of(
        run("mvn test", TestRunStatus.PASSED, 1),
        run("mvn test", TestRunStatus.FAILED, 5)))).isTrue();
    assertThat(WorkflowEvidenceService.currentFailure(List.of(
        run("mvn test", TestRunStatus.TIMED_OUT, 1)))).isTrue();
  }

  @Test
  void eachCommandIsJudgedByItsOwnLatestRunAndUnfinishedRunsAreIgnored() {
    assertThat(WorkflowEvidenceService.currentFailure(List.of(
        run("mvn test", TestRunStatus.PASSED, 5),
        run("./gradlew test", TestRunStatus.FAILED, 3)))).isTrue();
    assertThat(WorkflowEvidenceService.currentFailure(List.of(
        run("mvn test", TestRunStatus.PASSED, 1),
        run("mvn test", TestRunStatus.RUNNING, 9)))).isFalse();
    assertThat(WorkflowEvidenceService.currentFailure(List.of())).isFalse();
  }

  @Test
  void anUnsureFinalGateOrReviewGoesToTheUserNotToHoldOrRework() {
    assertThat(WorkflowCoordinator.unclear("HOLD", "APPROVED")).isTrue();
    assertThat(WorkflowCoordinator.unclear("PASS_WITH_WARNINGS", "APPROVED")).isTrue();
    assertThat(WorkflowCoordinator.unclear("PASS", "HOLD")).isFalse();
    assertThat(WorkflowCoordinator.unclear("HOLD", "HOLD")).isTrue();
  }

  @Test
  void aConcreteRequestForChangesIsNeverTreatedAsUnclear() {
    assertThat(WorkflowCoordinator.unclear("REVISION_REQUIRED", "APPROVED")).isFalse();
    assertThat(WorkflowCoordinator.unclear("HOLD", "CHANGES_REQUIRED")).isFalse();
    assertThat(WorkflowCoordinator.unclear("PASS", "APPROVED")).isFalse();
  }
}
