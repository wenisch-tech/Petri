package tech.wenisch.petri.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.git.provider.GitAccountConnectionService;
import tech.wenisch.petri.git.provider.ProjectProviderSource;
import tech.wenisch.petri.git.provider.ProjectProviderSourceService;
import tech.wenisch.petri.git.provider.ProviderRepository;
import tech.wenisch.petri.git.provider.PullRequestListing;
import tech.wenisch.petri.git.provider.PullRequestRef;

class AutomationSchedulerTest {
  private final ProjectAutomationRepository automations = mock(ProjectAutomationRepository.class);
  private final AutomationSettingsService settings = mock(AutomationSettingsService.class);
  private final AutomationRunService runs = mock(AutomationRunService.class);
  private final ProjectProviderSourceService sources = mock(ProjectProviderSourceService.class);
  private final GitAccountConnectionService connections = mock(GitAccountConnectionService.class);
  private final CodeReviewAutomation review = mock(CodeReviewAutomation.class);
  private final AutomationScheduler scheduler = new AutomationScheduler(
      automations, settings, runs, sources, connections, review, true, 5, 2);
  private final UUID projectId = UUID.randomUUID();

  private static PullRequestRef pull(long number, boolean draft) {
    return new PullRequestRef(number, "PR " + number, "b", "alice", "sha" + number, "f", "main",
        "refs/pull/" + number + "/head", draft, false, "https://git.test/" + number, Instant.now());
  }

  private ProjectAutomation automation(AutomationTrigger trigger) {
    ProjectAutomation automation = new ProjectAutomation(
        UUID.randomUUID(), projectId, AutomationKind.CODE_REVIEW, Instant.now().minusSeconds(3600));
    automation.update(true, trigger, "0 0 9 * * *", AutomationPostMode.AUTO, null, null, 20,
        Instant.now().minusSeconds(3600));
    return automation;
  }

  private void listing(PullRequestRef... pulls) {
    when(sources.find(projectId)).thenReturn(new ProjectProviderSource(projectId, UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(),
        new ProviderRepository("42", "demo", "acme/demo", "u", "w", "main", "private", false, true, true),
        Instant.now()));
    when(connections.openPullRequests(any(), anyString(), anyString()))
        .thenReturn(new PullRequestListing("bot", List.of(pulls)));
    when(runs.register(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
        .thenAnswer(call -> Optional.of(UUID.randomUUID()));
  }

  @Test
  void draftPullRequestsAreNeverQueued() {
    listing(pull(1, true), pull(2, false), pull(3, true));

    int queued = scheduler.runNow(automation(AutomationTrigger.PULL_REQUEST));

    assertThat(queued).isEqualTo(1);
    verify(runs).register(eq(projectId), eq(AutomationKind.CODE_REVIEW), any(),
        org.mockito.ArgumentMatchers.argThat(pr -> pr.number() == 2), any(),
        org.mockito.ArgumentMatchers.anyBoolean(), any());
    verify(runs, never()).register(any(), any(), any(),
        org.mockito.ArgumentMatchers.argThat(pr -> pr.draft()), any(),
        org.mockito.ArgumentMatchers.anyBoolean(), any());
    verify(review, timeout(2_000)).execute(org.mockito.ArgumentMatchers.argThat(
        job -> job.pr().number() == 2));
  }

  @Test
  void aPullRequestLeavingDraftIsPickedUpBecauseItWasNeverRegistered() {
    listing(pull(5, false));

    assertThat(scheduler.runNow(automation(AutomationTrigger.PULL_REQUEST))).isEqualTo(1);
  }

  @Test
  void aFailedReviewIsQueuedAgainForTheCurrentStateOfThePullRequest() {
    listing(pull(8, false));
    when(runs.register(any(), any(), any(), any(), any(),
        org.mockito.ArgumentMatchers.eq(true), any())).thenReturn(Optional.of(UUID.randomUUID()));
    AutomationRun failed = new AutomationRun(UUID.randomUUID(), projectId,
        AutomationKind.CODE_REVIEW, AutomationTrigger.PULL_REQUEST, "8", "old-head", "PR 8", null,
        AutomationPostMode.AUTO, Instant.now());
    failed.failed("provider said no", Instant.now());

    scheduler.retry(automation(AutomationTrigger.PULL_REQUEST), failed);

    verify(review, timeout(2_000)).execute(org.mockito.ArgumentMatchers.argThat(
        job -> job.pr().number() == 8 && job.pr().headSha().equals("sha8")));
  }

  @Test
  void aRetryIsRefusedForClosedOrDraftPullRequests() {
    listing(pull(1, false), pull(2, true));
    AutomationRun missing = new AutomationRun(UUID.randomUUID(), projectId,
        AutomationKind.CODE_REVIEW, AutomationTrigger.PULL_REQUEST, "99", "h", "T", null,
        AutomationPostMode.AUTO, Instant.now());
    AutomationRun draft = new AutomationRun(UUID.randomUUID(), projectId,
        AutomationKind.CODE_REVIEW, AutomationTrigger.PULL_REQUEST, "2", "h", "T", null,
        AutomationPostMode.AUTO, Instant.now());
    ProjectAutomation automation = automation(AutomationTrigger.PULL_REQUEST);

    org.assertj.core.api.Assertions.assertThatIllegalStateException()
        .isThrownBy(() -> scheduler.retry(automation, missing)).withMessageContaining("closed or merged");
    org.assertj.core.api.Assertions.assertThatIllegalStateException()
        .isThrownBy(() -> scheduler.retry(automation, draft)).withMessageContaining("draft");
    verify(review, never()).execute(any());
  }
}

