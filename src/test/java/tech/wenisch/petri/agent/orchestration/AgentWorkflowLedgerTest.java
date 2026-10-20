package tech.wenisch.petri.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;
import tech.wenisch.petri.agent.change.ChangeProposalService;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.run.PackageInstallService;
import tech.wenisch.petri.agent.run.TerminalService;
import tech.wenisch.petri.agent.run.TestRunService;
import tech.wenisch.petri.audit.AuditLogService;

class AgentWorkflowLedgerTest {
  private final AgentWorkflowRepository workflows = mock(AgentWorkflowRepository.class);
  private final AgentWorkflowArtifactRepository artifacts = mock(AgentWorkflowArtifactRepository.class);
  private final AgentWorkflowTransitionRepository transitions = mock(AgentWorkflowTransitionRepository.class);
  private final AgentWorkflowRoleSnapshotRepository snapshots = mock(AgentWorkflowRoleSnapshotRepository.class);
  private final WorktreeExecutionReservationRepository reservations =
      mock(WorktreeExecutionReservationRepository.class);
  private final AgentChatRepository chats = mock(AgentChatRepository.class);
  private final AgentRunService runs = mock(AgentRunService.class);
  private final OrchestrationProfileService profiles = mock(OrchestrationProfileService.class);
  private final AuditLogService audit = mock(AuditLogService.class);
  private final TestRunService tests = mock(TestRunService.class);
  private final WorkflowGoalVerifier goalVerifier = mock(WorkflowGoalVerifier.class);
  private final UUID userId = UUID.randomUUID();
  private final UUID worktreeId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private AgentWorkflowLedger ledger;
  private AgentWorkflow workflow;

  @BeforeEach
  void setUp() {
    ledger = new AgentWorkflowLedger(workflows, artifacts, transitions, snapshots, reservations, chats,
        runs, profiles, audit, mock(ChangeProposalService.class), tests, mock(PackageInstallService.class),
        mock(TerminalService.class), goalVerifier);
    workflow = new AgentWorkflow(UUID.randomUUID(), UUID.randomUUID(), 0, worktreeId, "Do it", userId,
        Instant.now());
    when(workflows.findById(workflow.id())).thenReturn(Optional.of(workflow));
    when(workflows.findForUpdateById(workflow.id())).thenReturn(Optional.of(workflow));
    when(workflows.save(any())).thenAnswer(call -> call.getArgument(0));
    when(chats.save(any())).thenAnswer(call -> call.getArgument(0));
    when(artifacts.save(any())).thenAnswer(call -> call.getArgument(0));
    when(reservations.findById(worktreeId)).thenReturn(Optional.empty());
  }

  @Test
  void aNewWorkflowGetsItsOwnChatReservationSnapshotsAndAnAuditTrail() {
    UUID id = UUID.randomUUID();

    AgentWorkflow created = ledger.create(id, null, null, worktreeId, projectId,
        "  A   long\nprompt ", userId, "alice", null);

    assertThat(created.id()).isEqualTo(id);
    assertThat(created.currentStage()).isEqualTo(AgentWorkflowStage.QUEUED);
    ArgumentCaptor<AgentChat> chat = ArgumentCaptor.forClass(AgentChat.class);
    verify(chats).save(chat.capture());
    assertThat(chat.getValue().executionMode()).isEqualTo(AgentChatExecutionMode.ORCHESTRATED);
    verify(reservations).save(any(WorktreeExecutionReservation.class));
    verify(profiles).snapshot(id, projectId, userId, null);
    verify(transitions).save(any(AgentWorkflowTransition.class));
    verify(audit).record(eq("alice"), eq("AGENT_WORKFLOW_CREATED"), eq("AGENT_WORKFLOW"), eq(id), anyString());
  }

  @Test
  void aForkedWorkflowWithALongPromptStillGetsAnOrchestratedChat() {
    UUID parent = UUID.randomUUID();

    ledger.create(UUID.randomUUID(), null, parent, worktreeId, projectId, "x".repeat(300), userId, "a", null);

    ArgumentCaptor<AgentChat> chat = ArgumentCaptor.forClass(AgentChat.class);
    verify(chats).save(chat.capture());
    assertThat(chat.getValue().executionMode()).isEqualTo(AgentChatExecutionMode.ORCHESTRATED);
  }

  @Test
  void anExistingChatMustBelongToTheCreatorTheWorktreeAndBeOrchestrated() {
    UUID chatId = UUID.randomUUID();
    assertThatThrownBy(() -> ledger.create(UUID.randomUUID(), chatId, null, worktreeId, projectId, "p",
        userId, "a", null)).hasMessage("Unknown chat");

    when(chats.findForUpdateById(chatId)).thenReturn(Optional.of(
        new AgentChat(chatId, worktreeId, "t", UUID.randomUUID(), AgentChatExecutionMode.ORCHESTRATED, null, null, Instant.now())));
    assertThatThrownBy(() -> ledger.create(UUID.randomUUID(), chatId, null, worktreeId, projectId, "p",
        userId, "a", null)).hasMessageContaining("another creator");

    when(chats.findForUpdateById(chatId)).thenReturn(Optional.of(
        new AgentChat(chatId, UUID.randomUUID(), "t", userId, AgentChatExecutionMode.ORCHESTRATED, null, null, Instant.now())));
    assertThatThrownBy(() -> ledger.create(UUID.randomUUID(), chatId, null, worktreeId, projectId, "p",
        userId, "a", null)).hasMessageContaining("another worktree");

    when(chats.findForUpdateById(chatId)).thenReturn(Optional.of(
        new AgentChat(chatId, worktreeId, "t", userId, Instant.now())));
    assertThatThrownBy(() -> ledger.create(UUID.randomUUID(), chatId, null, worktreeId, projectId, "p",
        userId, "a", null)).hasMessageContaining("Direct chats");
  }

  @Test
  void aReservedWorktreeAcceptsOnlyAnotherWorkflowOfTheSameChat() {
    UUID chatId = UUID.randomUUID();
    AgentChat chat = new AgentChat(chatId, worktreeId, "t", userId,
        AgentChatExecutionMode.ORCHESTRATED, null, null, Instant.now());
    when(chats.findForUpdateById(chatId)).thenReturn(Optional.of(chat));
    WorktreeExecutionReservation reservation =
        new WorktreeExecutionReservation(worktreeId, workflow.id(), userId, Instant.now());
    when(reservations.findById(worktreeId)).thenReturn(Optional.of(reservation));

    assertThatThrownBy(() -> ledger.create(UUID.randomUUID(), chatId, null, worktreeId, projectId, "p",
        userId, "a", null)).hasMessageContaining("reserved by another workflow");

    AgentWorkflow sameChat = new AgentWorkflow(workflow.id(), chatId, 0, worktreeId, "p", userId, Instant.now());
    when(workflows.findById(workflow.id())).thenReturn(Optional.of(sameChat));
    ledger.create(UUID.randomUUID(), chatId, null, worktreeId, projectId, "p", userId, "a", null);
    verify(reservations, never()).save(any());

    when(workflows.findById(workflow.id())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> ledger.create(UUID.randomUUID(), chatId, null, worktreeId, projectId, "p",
        userId, "a", null)).hasMessageContaining("inconsistent");
  }

  @Test
  void transitionsAreRecordedAuditedAndBoundedAndTerminalOnesReleaseTheReservation() {
    WorktreeExecutionReservation reservation =
        new WorktreeExecutionReservation(worktreeId, workflow.id(), userId, Instant.now());
    when(reservations.findByWorkflowId(workflow.id())).thenReturn(Optional.of(reservation));

    ledger.transition(workflow.id(), AgentWorkflowStage.PLANNING, AgentWorkflowStatus.RUNNING,
        WorkflowGateOutcome.STARTED, "a", "line one\nline two");
    assertThat(workflow.holdReason()).isEqualTo("line one line two");
    verify(reservations, never()).delete(any());

    ledger.transition(workflow.id(), AgentWorkflowStage.COMPLETED, AgentWorkflowStatus.COMPLETED,
        WorkflowGateOutcome.PASS, "a", "x".repeat(3000));
    assertThat(workflow.holdReason()).hasSize(2000);
    verify(reservations).delete(reservation);
  }

  @Test
  void aNullTransitionReasonStaysNull() {
    ledger.transition(workflow.id(), AgentWorkflowStage.PLANNING, AgentWorkflowStatus.RUNNING,
        WorkflowGateOutcome.STARTED, "a", null);

    assertThat(workflow.holdReason()).isNull();
  }

  @Test
  void theBaselineIsCapturedOnTheWorkflow() {
    ledger.baseline(workflow.id(), "head", "tree", "a");

    assertThat(workflow.baselineHead()).isEqualTo("head");
    verify(audit).record(eq("a"), eq("AGENT_WORKFLOW_BASELINE_CREATED"), any(), any(), anyString());
  }

  @Test
  void unknownWorkflowsAreRejected() {
    assertThatThrownBy(() -> ledger.require(UUID.randomUUID())).hasMessage("Unknown workflow");
    assertThatThrownBy(() -> ledger.transition(UUID.randomUUID(), AgentWorkflowStage.PLANNING,
        AgentWorkflowStatus.RUNNING, WorkflowGateOutcome.STARTED, "a", null))
        .hasMessage("Unknown workflow");
  }

  @Test
  void ownershipIsEnforcedOnReadsAndLockedWrites() {
    assertThat(ledger.requireOwned(workflow.id(), userId)).isSameAs(workflow);
    assertThatThrownBy(() -> ledger.requireOwned(workflow.id(), UUID.randomUUID()))
        .hasMessage("Unknown workflow");
    assertThatThrownBy(() -> ledger.approvePlan(workflow.id(), 1, UUID.randomUUID(), "a"))
        .hasMessage("Unknown workflow");
  }

  @Test
  void storingAResultIsIdempotentPerRunAndANewPlanInvalidatesApproval() {
    UUID runId = UUID.randomUUID();
    AgentWorkflowArtifact existing = new AgentWorkflowArtifact(UUID.randomUUID(), workflow.id(), runId,
        WorkflowArtifactKind.PLAN, 1, "{}", "md", Instant.now());
    when(artifacts.findByAgentRunId(runId)).thenReturn(Optional.of(existing));
    assertThat(ledger.store(workflow.id(), runId, parsed(WorkflowArtifactKind.PLAN))).isSameAs(existing);
    verify(artifacts, never()).save(any());

    UUID other = UUID.randomUUID();
    when(artifacts.findByAgentRunId(other)).thenReturn(Optional.empty());
    when(artifacts.findByWorkflowIdAndKindOrderByRevisionAsc(workflow.id(), WorkflowArtifactKind.PLAN))
        .thenReturn(List.of(existing));
    AgentWorkflowArtifact stored = ledger.store(workflow.id(), other, parsed(WorkflowArtifactKind.PLAN));
    assertThat(stored.revision()).isEqualTo(2);
    assertThat(workflow.currentPlanRevision()).isEqualTo(2);

    ledger.store(UUID.fromString(workflow.id().toString()), UUID.randomUUID(),
        parsed(WorkflowArtifactKind.REVIEW));
    assertThat(workflow.currentPlanRevision()).isEqualTo(2);
  }

  @Test
  void aStageFailureIsRecordedOnceAndPutsARunningWorkflowBackToPending() {
    UUID runId = UUID.randomUUID();
    workflow.transition(AgentWorkflowStage.PLANNING, AgentWorkflowStatus.RUNNING, null, Instant.now());
    when(artifacts.findByAgentRunId(runId)).thenReturn(Optional.empty());

    ledger.recordStageFailure(workflow.id(), runId, "bad \"json\"\n", "a");

    ArgumentCaptor<AgentWorkflowArtifact> artifact = ArgumentCaptor.forClass(AgentWorkflowArtifact.class);
    verify(artifacts).save(artifact.capture());
    assertThat(artifact.getValue().kind()).isEqualTo(WorkflowArtifactKind.STAGE_FAILURE);
    assertThat(artifact.getValue().contentJson()).isEqualTo("{\"error\":\"bad \\\"json\\\"\"}");
    assertThat(workflow.status()).isEqualTo(AgentWorkflowStatus.PENDING);

    when(artifacts.findByAgentRunId(runId)).thenReturn(Optional.of(artifact.getValue()));
    ledger.recordStageFailure(workflow.id(), runId, null, "a");
    verify(artifacts, org.mockito.Mockito.times(1)).save(any());
  }

  @Test
  void aNullFailureReasonGetsAGenericMessage() {
    when(artifacts.findByAgentRunId(any())).thenReturn(Optional.empty());

    ledger.recordStageFailure(workflow.id(), UUID.randomUUID(), null, "a");

    ArgumentCaptor<AgentWorkflowArtifact> artifact = ArgumentCaptor.forClass(AgentWorkflowArtifact.class);
    verify(artifacts).save(artifact.capture());
    assertThat(artifact.getValue().contentJson()).isEqualTo("{\"error\":\"Stage failed\"}");
  }

  @Test
  void userArtifactsGetTheNextRevision() {
    when(artifacts.findByWorkflowIdAndKindOrderByRevisionAsc(workflow.id(), WorkflowArtifactKind.ANSWERS))
        .thenReturn(List.of());

    AgentWorkflowArtifact artifact = ledger.storeUserArtifact(workflow.id(),
        WorkflowArtifactKind.ANSWERS, "{}", "md");

    assertThat(artifact.revision()).isEqualTo(1);
    assertThat(artifact.agentRunId()).isNull();
  }

  @Test
  void planApprovalNeedsTheWaitingStageAndTheCurrentRevision() {
    assertThatThrownBy(() -> ledger.approvePlan(workflow.id(), 1, userId, "a"))
        .hasMessageContaining("not waiting for plan approval");

    workflow.transition(AgentWorkflowStage.WAITING_FOR_PLAN_APPROVAL,
        AgentWorkflowStatus.WAITING_FOR_USER, null, Instant.now());
    workflow.currentPlan(1, Instant.now());
    ledger.approvePlan(workflow.id(), 1, userId, "alice");
    assertThat(workflow.approvedPlanRevision()).isEqualTo(1);

    ledger.invalidateApproval(workflow.id());
    assertThat(workflow.approvedPlanRevision()).isNull();
  }

  @Test
  void queueLookupsReflectReservationsAndQueuedWorkflows() {
    when(workflows.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of(workflow));
    assertThat(ledger.recoverable()).containsExactly(workflow);
    when(workflows.findByChatIdOrderByChatMessageIndexAsc(workflow.chatId())).thenReturn(List.of(workflow));
    assertThat(ledger.chatWorkflows(workflow.chatId())).containsExactly(workflow);
    assertThat(ledger.hasReservation(workflow.id())).isFalse();

    when(reservations.existsById(worktreeId)).thenReturn(true);
    assertThat(ledger.reserveNext(worktreeId)).isNull();

    when(reservations.existsById(worktreeId)).thenReturn(false);
    when(workflows.findByExecutionWorktreeIdAndStatusAndCurrentStageOrderByCreatedAtAsc(
        worktreeId, AgentWorkflowStatus.PENDING, AgentWorkflowStage.QUEUED)).thenReturn(List.of());
    assertThat(ledger.reserveNext(worktreeId)).isNull();

    when(workflows.findByExecutionWorktreeIdAndStatusAndCurrentStageOrderByCreatedAtAsc(
        worktreeId, AgentWorkflowStatus.PENDING, AgentWorkflowStage.QUEUED)).thenReturn(List.of(workflow));
    assertThat(ledger.reserveNext(worktreeId)).isSameAs(workflow);
    verify(reservations).save(any(WorktreeExecutionReservation.class));
  }

  @Test
  void missingSnapshotsAndArtifactsAreReportedClearly() {
    assertThatThrownBy(() -> ledger.snapshot(workflow.id(), AgentWorkflowRole.PLANNER))
        .hasMessageContaining("snapshot is missing");
    when(artifacts.findFirstByWorkflowIdAndKindOrderByRevisionDesc(workflow.id(), WorkflowArtifactKind.PLAN))
        .thenReturn(Optional.empty());
    assertThatThrownBy(() -> ledger.latest(workflow.id(), WorkflowArtifactKind.PLAN))
        .hasMessageContaining("PLAN");
    assertThat(ledger.artifactForRun(UUID.randomUUID())).isNull();
  }

  @Test
  void attemptsCountRunsOfAPhaseAndAManualRetryStartsAFreshBudget() {
    Instant retried = Instant.now();
    AgentRunView before = run(AgentWorkflowStage.PLANNING, retried.minusSeconds(60));
    AgentRunView after = run(AgentWorkflowStage.PLANNING, retried.plusSeconds(60));
    AgentRunView other = run(AgentWorkflowStage.IMPLEMENTING, retried.plusSeconds(60));
    when(runs.workflowRuns(workflow.id())).thenReturn(List.of(before, after, other));

    assertThat(ledger.attempts(workflow.id(), AgentWorkflowStage.PLANNING)).isEqualTo(2);
    assertThat(ledger.attemptsSinceRetry(workflow.id(), AgentWorkflowStage.PLANNING)).isEqualTo(2);

    AgentWorkflowTransition retry = new AgentWorkflowTransition(UUID.randomUUID(), workflow.id(),
        AgentWorkflowStage.HUMAN_HOLD, AgentWorkflowStage.HUMAN_HOLD, WorkflowGateOutcome.USER_RETRIED,
        "a", "retry", retried);
    when(transitions.findByWorkflowIdOrderByCreatedAtAsc(workflow.id())).thenReturn(List.of(retry));
    assertThat(ledger.attemptsSinceRetry(workflow.id(), AgentWorkflowStage.PLANNING)).isEqualTo(1);
  }

  @Test
  void goalsAreEmptyWithoutAPlanAndStaleReviewsAreFlagged() {
    assertThat(ledger.goals(workflow.id())).isEmpty();

    Instant earlier = Instant.now().minusSeconds(100);
    AgentWorkflowArtifact plan = artifact(WorkflowArtifactKind.PLAN, "{\"p\":1}", earlier);
    AgentWorkflowArtifact review = artifact(WorkflowArtifactKind.REVIEW, "{\"r\":1}", earlier);
    AgentWorkflowArtifact diff = artifact(WorkflowArtifactKind.TASK_DIFF, "{\"d\":1}", Instant.now());
    when(artifacts.findFirstByWorkflowIdAndKindOrderByRevisionDesc(workflow.id(), WorkflowArtifactKind.PLAN))
        .thenReturn(Optional.of(plan));
    when(artifacts.findFirstByWorkflowIdAndKindOrderByRevisionDesc(workflow.id(), WorkflowArtifactKind.REVIEW))
        .thenReturn(Optional.of(review));
    when(artifacts.findFirstByWorkflowIdAndKindOrderByRevisionDesc(workflow.id(), WorkflowArtifactKind.TASK_DIFF))
        .thenReturn(Optional.of(diff));
    when(goalVerifier.verify(any(), any(), anyBoolean(), any(), any())).thenReturn(List.of());

    ledger.goals(workflow.id());

    verify(goalVerifier).verify(eq("{\"p\":1}"), eq("{\"r\":1}"), eq(true), eq("{\"d\":1}"), any());
  }

  @Test
  void theViewCollectsArtifactsTransitionsRunsAndGoals() {
    AgentRunView run = run(AgentWorkflowStage.PLANNING, Instant.now());
    when(runs.workflowRuns(workflow.id())).thenReturn(List.of(run));
    when(artifacts.findByWorkflowIdOrderByCreatedAtAsc(workflow.id())).thenReturn(List.of(
        artifact(WorkflowArtifactKind.PLANNER_BRIEF, "{}", Instant.now())));
    when(transitions.findByWorkflowIdOrderByCreatedAtAsc(workflow.id())).thenReturn(List.of(
        new AgentWorkflowTransition(UUID.randomUUID(), workflow.id(), null, AgentWorkflowStage.QUEUED,
            WorkflowGateOutcome.STARTED, "a", "created", Instant.now())));

    AgentWorkflowView view = ledger.view(workflow.id(), userId);

    assertThat(view.id()).isEqualTo(workflow.id());
    assertThat(view.artifacts()).hasSize(1);
    assertThat(view.transitions()).hasSize(1);
    assertThat(view.stageRuns()).containsExactly(run);
    assertThat(view.goals()).isEmpty();
    verify(tests).listForAgentRun(run.id());
  }

  private static boolean anyBoolean() { return org.mockito.ArgumentMatchers.anyBoolean(); }

  private WorkflowArtifactParser.Parsed parsed(WorkflowArtifactKind kind) {
    return new WorkflowArtifactParser.Parsed(kind, "{}", "md", null);
  }

  private AgentWorkflowArtifact artifact(WorkflowArtifactKind kind, String json, Instant at) {
    return new AgentWorkflowArtifact(UUID.randomUUID(), workflow.id(), null, kind, 1, json, "md", at);
  }

  private AgentRunView run(AgentWorkflowStage phase, Instant createdAt) {
    return new AgentRunView(UUID.randomUUID(), worktreeId, ModelProvider.OPENAI, null, "m", "p",
        AgentRunStatus.COMPLETED, 10, 100, null, null, 0, null, null, userId, createdAt, null, null,
        List.of(), UUID.randomUUID(), 0, 100, null, workflow.id(), AgentWorkflowRole.PLANNER, phase, 1,
        0, 0, null);
  }
}
