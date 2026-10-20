package tech.wenisch.petri.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.git.ProjectWorktreeStatus;
import tech.wenisch.petri.git.ProjectWorktreeView;
import tech.wenisch.petri.git.WorkflowGitBaselineService;
import tech.wenisch.petri.git.WorkspaceGitService;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tools.jackson.databind.ObjectMapper;

class AgentWorkflowServiceTest {
  private final AgentWorkflowLedger ledger = mock(AgentWorkflowLedger.class);
  private final WorkflowCoordinator coordinator = mock(WorkflowCoordinator.class);
  private final WorkflowGitBaselineService baselines = mock(WorkflowGitBaselineService.class);
  private final ProjectWorktreeService worktrees = mock(ProjectWorktreeService.class);
  private final WorkspaceGitService git = mock(WorkspaceGitService.class);
  private final ProjectRepository projects = mock(ProjectRepository.class);
  private final AgentRunService runs = mock(AgentRunService.class);
  private final AgentChatStream stream = mock(AgentChatStream.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final UUID userId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID worktreeId = UUID.randomUUID();
  private final AgentWorkflowView view = mock(AgentWorkflowView.class);
  private AgentWorkflowService service;
  private AgentWorkflow workflow;

  @BeforeEach
  void setUp() {
    service = new AgentWorkflowService(
        ledger, coordinator, baselines, worktrees, git, projects, runs, stream, mapper);
    workflow = new AgentWorkflow(UUID.randomUUID(), UUID.randomUUID(), 0, worktreeId, "Do it",
        userId, Instant.now());
    when(projects.findById(projectId)).thenReturn(java.util.Optional.of(
        new Project(projectId, "Demo", "demo", "https://example.test/a.git", "main", userId, Instant.now())));
    when(worktrees.get(worktreeId)).thenReturn(worktree(worktreeId, "feature"));
    when(ledger.create(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(workflow);
    when(ledger.baseline(any(), any(), any(), any())).thenReturn(workflow);
    when(ledger.view(any(UUID.class), any(UUID.class))).thenReturn(view);
    when(ledger.requireOwned(workflow.id(), userId)).thenReturn(workflow);
    when(ledger.transition(any(), any(), any(), any(), any(), any())).thenReturn(workflow);
  }

  @Test
  void createRejectsMissingPromptsAndUnknownProjects() {
    assertThatThrownBy(() -> service.create(worktreeId, command(null, " ", null, null, false), userId, "a"))
        .hasMessageContaining("Prompt is required");
    assertThatThrownBy(() -> service.create(worktreeId,
        command(null, "x".repeat(200_001), null, null, false), userId, "a"))
        .hasMessageContaining("too long");

    when(projects.findById(projectId)).thenReturn(java.util.Optional.empty());
    assertThatThrownBy(() -> service.create(worktreeId, command(null, "Do", null, null, false), userId, "a"))
        .hasMessage("Unknown project");
  }

  @Test
  void aWorkflowOnTheDefaultBranchNeedsExplicitConfirmation() {
    when(worktrees.get(worktreeId)).thenReturn(worktree(worktreeId, "main"));

    assertThatThrownBy(() -> service.create(worktreeId, command(null, "Do", null, null, false), userId, "a"))
        .hasMessageContaining("default branch");

    service.create(worktreeId, command(null, "Do", null, null, true), userId, "a");
    verify(coordinator, never()).start(any());
  }

  @Test
  void aReservedWorkflowGetsABaselineAndStartsImmediately() {
    when(ledger.hasReservation(workflow.id())).thenReturn(true);
    when(baselines.create(worktreeId, workflow.id()))
        .thenReturn(new WorkflowGitBaselineService.Baseline("head", "tree"));

    AgentWorkflowStartView start = service.create(worktreeId, command(null, " Do it ", null, null, false),
        userId, "alice");

    verify(ledger).baseline(workflow.id(), "head", "tree", "alice");
    verify(coordinator).start(workflow.id());
    assertThat(start.workflow()).isSameAs(view);
    assertThat(start.worktreeId()).isEqualTo(worktreeId);
    assertThat(start.navigationUrl()).isEqualTo("/workspace/projects/" + projectId
        + "?worktree=" + worktreeId + "&chat=" + workflow.chatId());
  }

  @Test
  void aWorkflowThatCannotGetABaselineIsHeld() {
    when(ledger.hasReservation(workflow.id())).thenReturn(true);
    when(baselines.create(any(), any())).thenThrow(new IllegalStateException("no git\nrepo"));

    service.create(worktreeId, command(null, "Do", null, null, false), userId, "alice");

    verify(ledger).transition(eq(workflow.id()), eq(AgentWorkflowStage.HUMAN_HOLD),
        eq(AgentWorkflowStatus.HUMAN_HOLD), eq(WorkflowGateOutcome.HOLD), eq("alice"), eq("no git repo"));
    verify(coordinator, never()).start(any());
  }

  @Test
  void aBlankBaselineErrorGetsADefaultHoldReason() {
    when(ledger.hasReservation(workflow.id())).thenReturn(true);
    when(baselines.create(any(), any())).thenThrow(new IllegalStateException());

    service.create(worktreeId, command(null, "Do", null, null, false), userId, "alice");

    verify(ledger).transition(any(), any(), any(), any(), any(), eq("Could not prepare workflow baseline"));
  }

  @Test
  void aNewBranchWorkflowForksTheParentChatContext() {
    UUID parentChat = UUID.randomUUID();
    UUID branchWorktree = UUID.randomUUID();
    when(git.createBranch(projectId, "task", "feature", "alice")).thenReturn(worktree(branchWorktree, "task"));
    when(runs.chat(parentChat, userId)).thenReturn(chat(parentChat, AgentChatExecutionMode.DIRECT));
    when(runs.chatRuns(parentChat)).thenReturn(List.of());
    when(ledger.chatWorkflows(parentChat)).thenReturn(List.of(workflow));
    when(runs.chatSummaries(parentChat)).thenReturn(List.of());

    service.create(worktreeId, command(parentChat, "Do", WorkflowBranchMode.NEW, "task", false), userId, "alice");

    verify(ledger).create(any(), eq(null), eq(parentChat), eq(branchWorktree), eq(projectId),
        eq("Do"), eq(userId), eq("alice"), any());
    verify(ledger).storeUserArtifact(eq(workflow.id()), eq(WorkflowArtifactKind.CHAT_FORK_CONTEXT),
        contains("parentChatId"), contains("Context handed off"));
  }

  @Test
  void aNewBranchNeedsAName() {
    assertThatThrownBy(() -> service.create(worktreeId,
        command(null, "Do", WorkflowBranchMode.NEW, " ", false), userId, "a"))
        .hasMessageContaining("Branch name is required");
  }

  @Test
  void aDirectChatIsForkedWhileAnOrchestratedChatIsReused() {
    UUID direct = UUID.randomUUID();
    when(runs.chat(direct, userId)).thenReturn(chat(direct, AgentChatExecutionMode.DIRECT));
    service.create(worktreeId, command(direct, "Do", null, null, false), userId, "a");
    verify(ledger).create(any(), eq(null), eq(direct), any(), any(), any(), any(), any(), any());

    UUID orchestrated = UUID.randomUUID();
    when(runs.chat(orchestrated, userId)).thenReturn(chat(orchestrated, AgentChatExecutionMode.ORCHESTRATED));
    service.create(worktreeId, command(orchestrated, "Do", null, null, false), userId, "a");
    verify(ledger).create(any(), eq(orchestrated), eq(null), any(), any(), any(), any(), any(), any());
  }

  @Test
  void readsAreDelegatedAndChatWorkflowsAreOwnerChecked() {
    UUID chatId = UUID.randomUUID();
    when(ledger.chatWorkflows(chatId)).thenReturn(List.of(workflow));
    when(ledger.view(workflow)).thenReturn(view);

    assertThat(service.get(workflow.id(), userId)).isSameAs(view);
    assertThat(service.chatWorkflows(chatId, userId)).containsExactly(view);
    verify(runs).chat(chatId, userId);
  }

  @Test
  void answersNeedAWorkflowThatAsksForThem() {
    assertThatThrownBy(() -> service.answer(workflow.id(), userId, mapper.readTree("{\"q1\":\"a\"}"), "a"))
        .hasMessageContaining("not waiting for clarification");

    workflow.transition(AgentWorkflowStage.WAITING_FOR_CLARIFICATION,
        AgentWorkflowStatus.WAITING_FOR_USER, null, Instant.now());
    assertThatThrownBy(() -> service.answer(workflow.id(), userId, mapper.readTree("{}"), "a"))
        .hasMessageContaining("answers are required");
    assertThatThrownBy(() -> service.answer(workflow.id(), userId, null, "a"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(service.answer(workflow.id(), userId, mapper.readTree("{\"q1\":\"a\"}"), "alice")).isSameAs(view);
    verify(ledger).storeUserArtifact(eq(workflow.id()), eq(WorkflowArtifactKind.ANSWERS),
        eq("{\"q1\":\"a\"}"), anyString());
    verify(coordinator).clarificationAnswered(workflow.id(), "alice");
  }

  @Test
  void planAndFinalApprovalsAndRetryAndAcceptGoThroughTheCoordinator() {
    service.approvePlan(workflow.id(), 2, userId, "alice");
    verify(ledger).approvePlan(workflow.id(), 2, userId, "alice");
    verify(coordinator).planApproved(workflow.id(), "alice");

    service.approveFinal(workflow.id(), userId, "alice");
    verify(coordinator).finalApproved(workflow.id(), "alice");

    service.retry(workflow.id(), userId, "alice");
    verify(coordinator).retry(workflow.id(), "alice");

    service.accept(workflow.id(), userId, "alice");
    verify(coordinator).acceptHeld(workflow.id(), "alice");
  }

  @Test
  void aPlanRevisionNeedsFeedbackAndAWaitingPlan() {
    assertThatThrownBy(() -> service.revisePlan(workflow.id(), "more", userId, "a"))
        .hasMessageContaining("not waiting for plan approval");

    workflow.transition(AgentWorkflowStage.WAITING_FOR_PLAN_APPROVAL,
        AgentWorkflowStatus.WAITING_FOR_USER, null, Instant.now());
    assertThatThrownBy(() -> service.revisePlan(workflow.id(), " ", userId, "a"))
        .hasMessageContaining("feedback is required");

    service.revisePlan(workflow.id(), " split it ", userId, "alice");
    verify(ledger).storeUserArtifact(eq(workflow.id()), eq(WorkflowArtifactKind.USER_GUIDANCE),
        eq("{\"feedback\":\"split it\"}"), eq("split it"));
    verify(coordinator).planRevisionRequested(workflow.id(), "alice");
  }

  @Test
  void guidanceCancelsActiveRunsAndTellsTheCoordinatorWhetherOneWasRunning() {
    AgentRunView running = run(AgentRunStatus.RUNNING);
    AgentRunView pending = run(AgentRunStatus.PENDING);
    AgentRunView done = run(AgentRunStatus.COMPLETED);
    when(runs.workflowRuns(workflow.id())).thenReturn(List.of(running, pending, done));

    service.guidance(workflow.id(), "be careful", userId, "alice");

    verify(runs).requestCancellation(running.id(), userId, "alice");
    verify(runs).requestCancellation(pending.id(), userId, "alice");
    verify(runs, never()).requestCancellation(done.id(), userId, "alice");
    verify(coordinator).guidanceRequested(workflow.id(), true, "alice");
  }

  @Test
  void guidanceWithOnlyPendingRunsIsNotWaitingForAnActiveRun() {
    when(runs.workflowRuns(workflow.id())).thenReturn(List.of(run(AgentRunStatus.PENDING)));

    service.guidance(workflow.id(), "go", userId, "alice");

    verify(coordinator).guidanceRequested(workflow.id(), false, "alice");
  }

  @Test
  void guidanceIsRejectedForFinishedWorkflowsAndBlankText() {
    assertThatThrownBy(() -> service.guidance(workflow.id(), " ", userId, "a"))
        .hasMessageContaining("Guidance is required");

    workflow.transition(AgentWorkflowStage.COMPLETED, AgentWorkflowStatus.COMPLETED, null, Instant.now());
    assertThatThrownBy(() -> service.guidance(workflow.id(), "go", userId, "a"))
        .hasMessageContaining("already terminal");
  }

  @Test
  void cancellingRequestsCancellationOfActiveRunsAndMarksTheWorkflow() {
    AgentRunView running = run(AgentRunStatus.RUNNING);
    AgentRunView requested = run(AgentRunStatus.CANCELLATION_REQUESTED);
    AgentRunView done = run(AgentRunStatus.FAILED);
    when(runs.workflowRuns(workflow.id())).thenReturn(List.of(running, requested, done));

    service.cancel(workflow.id(), userId, "alice");

    verify(runs).requestCancellation(running.id(), userId, "alice");
    verify(runs).requestCancellation(requested.id(), userId, "alice");
    verify(runs, never()).requestCancellation(done.id(), userId, "alice");
    verify(ledger).transition(eq(workflow.id()), eq(workflow.currentStage()),
        eq(AgentWorkflowStatus.CANCELLING), eq(WorkflowGateOutcome.USER_CANCELLED), eq("alice"), anyString());
    verify(stream).workflowStatus(eq(workflow.chatId()), any());
    verify(coordinator).cancelWhenSafe(workflow.id(), "alice");
  }

  @Test
  void cancellingAFinishedWorkflowChangesNothing() {
    workflow.transition(AgentWorkflowStage.COMPLETED, AgentWorkflowStatus.COMPLETED, null, Instant.now());

    assertThat(service.cancel(workflow.id(), userId, "alice")).isSameAs(view);

    verify(coordinator, never()).cancelWhenSafe(any(), any());
  }

  private CreateAgentWorkflowCommand command(
      UUID chatId, String prompt, WorkflowBranchMode mode, String branch, boolean confirmed) {
    return new CreateAgentWorkflowCommand(chatId, prompt, mode, branch, confirmed, null);
  }

  private ProjectWorktreeView worktree(UUID id, String branch) {
    return new ProjectWorktreeView(id, projectId, "abc123", branch, null, false,
        ProjectWorktreeStatus.ACTIVE, Instant.now(), null);
  }

  private AgentChatView chat(UUID id, AgentChatExecutionMode mode) {
    return new AgentChatView(id, worktreeId, "title", false, false, false, false, mode, null, null,
        0, false, userId, Instant.now(), Instant.now(), List.of());
  }

  private AgentRunView run(AgentRunStatus status) {
    return new AgentRunView(UUID.randomUUID(), worktreeId, ModelProvider.OPENAI, null, "m", "p", status,
        10, 100, null, null, 0, null, null, userId, Instant.now(), null, null, List.of(),
        UUID.randomUUID(), 0, 100, null, workflow.id(), AgentWorkflowRole.PLANNER,
        AgentWorkflowStage.PLANNING, 1, 0, 0, null);
  }
}
