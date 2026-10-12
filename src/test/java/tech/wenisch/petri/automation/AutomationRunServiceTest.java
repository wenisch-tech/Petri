package tech.wenisch.petri.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.git.provider.PullRequestRef;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUserRepository;

@SpringBootTest
@Transactional
class AutomationRunServiceTest {
  @Autowired AutomationRunService runs;
  @Autowired AutomationRunRepository repository;
  @Autowired ProjectRepository projects;
  @Autowired AppUserRepository users;

  @Test
  void eachHeadCommitIsReviewedOnceAndANewPushCreatesANewRun() {
    UUID projectId = project();
    PullRequestRef first = pull(7, "aaa111");

    var id = runs.register(projectId, AutomationKind.CODE_REVIEW, AutomationTrigger.PULL_REQUEST,
        first, AutomationPostMode.AUTO, false, Set.of());
    assertThat(id).isPresent();
    assertThat(runs.register(projectId, AutomationKind.CODE_REVIEW, AutomationTrigger.PULL_REQUEST,
        first, AutomationPostMode.AUTO, false, Set.of())).isEmpty();

    assertThat(runs.register(projectId, AutomationKind.CODE_REVIEW, AutomationTrigger.PULL_REQUEST,
        pull(7, "bbb222"), AutomationPostMode.AUTO, false, Set.of())).isPresent();
  }

  @Test
  void failedRunsAreRetriedOnlyWhenForced() {
    UUID projectId = project();
    PullRequestRef pull = pull(9, "ccc333");
    UUID id = runs.register(projectId, AutomationKind.CODE_REVIEW, AutomationTrigger.PULL_REQUEST,
        pull, AutomationPostMode.AUTO, false, Set.of()).orElseThrow();
    runs.failed(id, "provider said no");

    assertThat(runs.register(projectId, AutomationKind.CODE_REVIEW, AutomationTrigger.PULL_REQUEST,
        pull, AutomationPostMode.AUTO, false, Set.of())).isEmpty();
    assertThat(runs.register(projectId, AutomationKind.CODE_REVIEW, AutomationTrigger.PULL_REQUEST,
        pull, AutomationPostMode.DRAFT, true, Set.of())).contains(id);
    AutomationRun run = repository.findById(id).orElseThrow();
    assertThat(run.getStatus()).isEqualTo(AutomationRunStatus.QUEUED);
    assertThat(run.getError()).isNull();
    assertThat(run.getPostMode()).isEqualTo(AutomationPostMode.DRAFT);
  }

  @Test
  void interruptedRunsAreRestartedOnlyWhenStaleAndNotInFlight() {
    UUID projectId = project();
    PullRequestRef pull = pull(11, "ddd444");
    UUID id = runs.register(projectId, AutomationKind.CODE_REVIEW, AutomationTrigger.PULL_REQUEST,
        pull, AutomationPostMode.AUTO, false, Set.of()).orElseThrow();

    // Fresh run: leave it alone.
    assertThat(runs.register(projectId, AutomationKind.CODE_REVIEW, AutomationTrigger.PULL_REQUEST,
        pull, AutomationPostMode.AUTO, false, Set.of())).isEmpty();

    AutomationRun run = repository.findById(id).orElseThrow();
    org.springframework.test.util.ReflectionTestUtils.setField(
        run, "updatedAt", Instant.now().minusSeconds(3600));
    repository.saveAndFlush(run);

    assertThat(runs.register(projectId, AutomationKind.CODE_REVIEW, AutomationTrigger.PULL_REQUEST,
        pull, AutomationPostMode.AUTO, false, Set.of(id))).isEmpty();
    assertThat(runs.register(projectId, AutomationKind.CODE_REVIEW, AutomationTrigger.PULL_REQUEST,
        pull, AutomationPostMode.AUTO, false, Set.of())).contains(id);
  }

  private UUID project() {
    var admin = users.findByEmailIgnoreCase("test-admin@petri.local").orElseThrow();
    UUID id = UUID.randomUUID();
    Project project = projects.save(new Project(id, "Automation test",
        "automation-test-" + id, "https://example.test/acme/repo.git", "main",
        admin.getId(), Instant.now()));
    return project.getId();
  }

  private static PullRequestRef pull(long number, String sha) {
    return new PullRequestRef(number, "Title " + number, "Body", "alice", sha, "feature", "main",
        "refs/pull/" + number + "/head", false, false, "https://example.test/pull/" + number,
        Instant.now());
  }

  @Test
  void anInterruptedReviewIsResumedEvenWhenThePullRequestDidNotChange() {
    UUID projectId = project();
    PullRequestRef pull = pull(21, "eee555");
    UUID id = runs.register(projectId, AutomationKind.CODE_REVIEW, AutomationTrigger.PULL_REQUEST,
        pull, AutomationPostMode.AUTO, false, Set.of()).orElseThrow();

    assertThat(runs.isStaleActive(projectId, AutomationKind.CODE_REVIEW, 21, "eee555", Set.of()))
        .isFalse();

    AutomationRun run = repository.findById(id).orElseThrow();
    org.springframework.test.util.ReflectionTestUtils.setField(
        run, "updatedAt", Instant.now().minusSeconds(3600));
    repository.saveAndFlush(run);

    assertThat(runs.isStaleActive(projectId, AutomationKind.CODE_REVIEW, 21, "eee555", Set.of()))
        .isTrue();
    assertThat(runs.isStaleActive(projectId, AutomationKind.CODE_REVIEW, 21, "eee555", Set.of(id)))
        .isFalse();
    assertThat(runs.isStaleActive(projectId, AutomationKind.CODE_REVIEW, 21, "other", Set.of()))
        .isFalse();
  }
}

