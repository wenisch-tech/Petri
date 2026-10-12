package tech.wenisch.petri.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.agent.model.ModelCredentialStatus;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.orchestration.AgentRunExecutor;
import tech.wenisch.petri.agent.orchestration.AgentRunService;
import tech.wenisch.petri.agent.orchestration.AgentRunStatus;
import tech.wenisch.petri.agent.orchestration.AgentRunView;
import tech.wenisch.petri.agent.orchestration.CreateAgentRunCommand;
import tech.wenisch.petri.agent.skill.SkillDocument;
import tech.wenisch.petri.agent.skill.SkillService;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.git.ProjectMirrorService;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.git.ProjectWorktreeView;
import tech.wenisch.petri.git.PullRequestDiffService;
import tech.wenisch.petri.git.provider.GitAccountConnectionService;
import tech.wenisch.petri.git.provider.ProjectProviderSource;
import tech.wenisch.petri.git.provider.ProjectProviderSourceService;
import tech.wenisch.petri.git.provider.ProviderRepository;
import tech.wenisch.petri.git.provider.PullRequestRef;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

class CodeReviewAutomationTest {
  private final AutomationRunService runs = mock(AutomationRunService.class);
  private final AutomationReviewService reviews = mock(AutomationReviewService.class);
  private final ProjectRepository projects = mock(ProjectRepository.class);
  private final AppUserRepository users = mock(AppUserRepository.class);
  private final ProjectProviderSourceService sources = mock(ProjectProviderSourceService.class);
  private final GitAccountConnectionService connections = mock(GitAccountConnectionService.class);
  private final ProjectMirrorService mirrors = mock(ProjectMirrorService.class);
  private final PullRequestDiffService diffs = mock(PullRequestDiffService.class);
  private final ProjectWorktreeService worktrees = mock(ProjectWorktreeService.class);
  private final SkillService skills = mock(SkillService.class);
  private final AgentRunService agentRuns = mock(AgentRunService.class);
  private final AgentRunExecutor executor = mock(AgentRunExecutor.class);
  private final ModelCredentialService models = mock(ModelCredentialService.class);
  private final AuditLogService audit = mock(AuditLogService.class);
  private final CodeReviewAutomation automation = new CodeReviewAutomation(
      runs, reviews, projects, users, sources, connections, mirrors, diffs, worktrees, skills, agentRuns,
      executor, models, audit, 100_000);

  private final UUID projectId = UUID.randomUUID();
  private final UUID ownerId = UUID.randomUUID();
  private final UUID runId = UUID.randomUUID();
  private final UUID worktreeId = UUID.randomUUID();
  private final UUID agentRunId = UUID.randomUUID();
  private final PullRequestRef pr = new PullRequestRef(7, "Add cache", "Body", "alice", "abc123",
      "feature", "main", "refs/pull/7/head", false, false, "https://git.test/pull/7", Instant.now());

  @BeforeEach
  void wire() {
    Project project = new Project(projectId, "Demo", "demo", "https://git.test/acme/demo.git",
        "main", ownerId, Instant.now());
    when(projects.findById(projectId)).thenReturn(Optional.of(project));
    when(users.findById(ownerId)).thenReturn(Optional.of(
        AppUser.oidcUser(ownerId, "owner@petri.local", "USER")));
    when(sources.find(projectId)).thenReturn(new ProjectProviderSource(
        projectId, UUID.randomUUID(), UUID.randomUUID(), ownerId,
        new ProviderRepository("42", "demo", "acme/demo", "https://git.test/acme/demo.git",
            "https://git.test/acme/demo", "main", "private", false, true, true),
        Instant.now()));
    when(diffs.diff(eq(projectId), eq(7L), eq("main"), anyInt())).thenReturn(
        new PullRequestDiffService.PullRequestDiff("abc123", List.of("src/A.java"), "diff", false));
    open(pr);
    ProjectWorktreeView worktree = mock(ProjectWorktreeView.class);
    when(worktree.id()).thenReturn(worktreeId);
    when(worktrees.create(eq(projectId), eq("abc123"), anyString())).thenReturn(worktree);
    when(skills.load("code-review")).thenReturn(new SkillDocument("code-review", "d", "SKILL BODY"));
    ModelCredentialStatus model = mock(ModelCredentialStatus.class);
    when(model.provider()).thenReturn(ModelProvider.OLLAMA);
    when(model.id()).thenReturn(UUID.randomUUID());
    when(model.global()).thenReturn(true);
    when(models.usable(ownerId)).thenReturn(List.of(model));
    AgentRunView created = mock(AgentRunView.class);
    when(created.id()).thenReturn(agentRunId);
    when(agentRuns.createAutomationRun(any(), any(), any(), anyString(), any(), anyString()))
        .thenReturn(created);
  }

  private void open(PullRequestRef current) {
    when(connections.openPullRequests(any(), anyString(), anyString())).thenReturn(
        new tech.wenisch.petri.git.provider.PullRequestListing("bot", List.of(current)));
  }

  private PullRequestRef withState(String sha, boolean draft) {
    return new PullRequestRef(7, "Add cache", "Body", "alice", sha, "feature", "main",
        "refs/pull/7/head", draft, false, "https://git.test/pull/7", Instant.now());
  }

  private CodeReviewAutomation.Job job(AutomationPostMode mode) {
    return new CodeReviewAutomation.Job(runId, projectId, pr, mode, null, null, 20);
  }

  private void agentFinishes(AgentRunStatus status, String review) {
    AgentRunView finished = mock(AgentRunView.class);
    when(finished.status()).thenReturn(status);
    when(executor.execute(eq(agentRunId), eq(worktreeId), eq(ownerId), anyString()))
        .thenReturn(finished);
    AutomationRun run = new AutomationRun(runId, projectId, AutomationKind.CODE_REVIEW,
        AutomationTrigger.PULL_REQUEST, "7", "abc123", "Add cache", pr.webUrl(),
        AutomationPostMode.AUTO, Instant.now());
    if (review != null) run.review(review, Instant.now());
    when(runs.require(runId)).thenReturn(run);
  }

  @Test
  void postsTheReviewAutomaticallyAndCleansUp() {
    agentFinishes(AgentRunStatus.COMPLETED, "REVIEW");
    when(connections.postPullRequestComment(any(), eq("42"), eq("acme/demo"), eq(7L), anyString(),
        eq("REVIEW"))).thenReturn("https://git.test/pull/7#c1");

    automation.execute(job(AutomationPostMode.AUTO));

    verify(runs).posted(runId, "https://git.test/pull/7#c1");
    verify(runs, never()).failed(any(), anyString());
    verify(worktrees).remove(worktreeId, projectId, "owner@petri.local");
    var command = org.mockito.ArgumentCaptor.forClass(CreateAgentRunCommand.class);
    verify(agentRuns).createAutomationRun(eq(worktreeId), command.capture(), eq(ownerId),
        anyString(), eq(runId), org.mockito.ArgumentMatchers.contains("SKILL BODY"));
    assertThat(command.getValue().prompt()).contains("Add cache", "src/A.java", "diff");
  }

  @Test
  void keepsADraftUntilApproved() {
    agentFinishes(AgentRunStatus.COMPLETED, "REVIEW");

    automation.execute(job(AutomationPostMode.DRAFT));

    verify(runs).awaitingApproval(runId);
    verify(connections, never()).postPullRequestComment(
        any(), anyString(), anyString(), anyLong(), any(), anyString());
    verify(worktrees).remove(worktreeId, projectId, "owner@petri.local");
  }

  @Test
  void failsWhenTheAgentNeverSubmittedAReview() {
    agentFinishes(AgentRunStatus.COMPLETED, null);

    automation.execute(job(AutomationPostMode.AUTO));

    verify(runs).failed(eq(runId), org.mockito.ArgumentMatchers.contains("without submitting"));
    verify(connections, never()).postPullRequestComment(
        any(), anyString(), anyString(), anyLong(), any(), anyString());
  }

  @Test
  void skipsWhenThePullRequestMovedWhileQueued() {
    when(diffs.diff(eq(projectId), eq(7L), eq("main"), anyInt())).thenReturn(
        new PullRequestDiffService.PullRequestDiff("zzz999", List.of("src/A.java"), "d", false));

    automation.execute(job(AutomationPostMode.AUTO));

    verify(runs).skipped(eq(runId), anyString());
    verify(worktrees, never()).create(any(), anyString(), anyString());
    verify(executor, never()).execute(any(), any(), any(), anyString());
  }

  @Test
  void recordsProviderFailuresInsteadOfThrowing() {
    org.mockito.Mockito.doThrow(new IllegalStateException("fetch denied"))
        .when(mirrors).fetchPullRequestRef(any(), anyString(), anyString());

    automation.execute(job(AutomationPostMode.AUTO));

    verify(runs).failed(runId, "fetch denied");
    verify(worktrees, never()).create(any(), anyString(), anyString());
  }

  @Test
  void reportsAFailedPostWithoutLosingTheReview() {
    agentFinishes(AgentRunStatus.COMPLETED, "REVIEW");
    when(connections.postPullRequestComment(any(), anyString(), anyString(), anyLong(), any(),
        anyString())).thenThrow(new tech.wenisch.petri.git.provider.GitProviderException(
            "Provider request failed with HTTP 403"));

    automation.execute(job(AutomationPostMode.AUTO));

    verify(runs).failed(eq(runId), org.mockito.ArgumentMatchers.contains("HTTP 403"));
  }

  @Test
  void looksAtThePullRequestWithAnEyesReactionBeforeStartingTheWorkflow() {
    agentFinishes(AgentRunStatus.COMPLETED, "REVIEW");

    automation.execute(job(AutomationPostMode.DRAFT));

    var order = org.mockito.Mockito.inOrder(connections, mirrors);
    order.verify(connections).reactToPullRequest(any(), eq("42"), eq("acme/demo"), eq(7L), eq("eyes"));
    order.verify(mirrors).synchronize(eq(projectId), anyString());
  }

  @Test
  void aFailingReactionDoesNotStopTheReview() {
    agentFinishes(AgentRunStatus.COMPLETED, "REVIEW");
    org.mockito.Mockito.doThrow(new tech.wenisch.petri.git.provider.GitProviderException("HTTP 403"))
        .when(connections).reactToPullRequest(any(), anyString(), anyString(), anyLong(), anyString());

    automation.execute(job(AutomationPostMode.DRAFT));

    verify(runs).awaitingApproval(runId);
  }

  @Test
  void doesNotReviewAPullRequestThatBecameADraftWhileQueued() {
    open(withState("abc123", true));

    automation.execute(job(AutomationPostMode.AUTO));

    verify(runs).skipped(eq(runId), org.mockito.ArgumentMatchers.contains("draft"));
    verify(connections, never()).reactToPullRequest(any(), anyString(), anyString(), anyLong(), anyString());
    verify(mirrors, never()).synchronize(any(), anyString());
  }

  @Test
  void skipsClosedPullRequestsAndNewerHeads() {
    when(connections.openPullRequests(any(), anyString(), anyString())).thenReturn(
        new tech.wenisch.petri.git.provider.PullRequestListing("bot", List.of()));
    automation.execute(job(AutomationPostMode.AUTO));
    verify(runs).skipped(eq(runId), org.mockito.ArgumentMatchers.contains("closed or merged"));

    open(withState("newer999", false));
    automation.execute(job(AutomationPostMode.AUTO));
    verify(runs).skipped(eq(runId), org.mockito.ArgumentMatchers.contains("updated"));
    verify(worktrees, never()).create(any(), anyString(), anyString());
  }

  @Test
  void aReReviewGetsThePreviousFindingsAndTheChangesSinceThen() {
    agentFinishes(AgentRunStatus.COMPLETED, "REVIEW");
    var previous = new ReviewSubmission.Previous("old11111", new ReviewSubmission(
        "was", ReviewSubmission.Verdict.REQUEST_CHANGES, List.of(new ReviewSubmission.Finding(
            "src/A.java", 4, ReviewSubmission.Severity.MAJOR, "Race condition", "b", null))));
    when(reviews.previous(runId)).thenReturn(Optional.of(previous));
    when(diffs.sinceCommit(eq(projectId), eq(7L), eq("old11111"), anyInt()))
        .thenReturn("+fixed line");

    automation.execute(job(AutomationPostMode.DRAFT));

    var command = org.mockito.ArgumentCaptor.forClass(CreateAgentRunCommand.class);
    verify(agentRuns).createAutomationRun(eq(worktreeId), command.capture(), eq(ownerId),
        anyString(), eq(runId), anyString());
    assertThat(command.getValue().prompt()).contains("New commits were pushed",
        "[1] MAJOR src/A.java:4 - Race condition", "comparison.resolved", "+fixed line", "old11111");
  }
}
