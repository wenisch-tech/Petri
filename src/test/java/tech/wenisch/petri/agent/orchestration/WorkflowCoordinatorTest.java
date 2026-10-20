package tech.wenisch.petri.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.run.TestRunStatus;
import tech.wenisch.petri.agent.run.TestRunView;
import tech.wenisch.petri.git.WorkflowGitBaselineService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WorkflowCoordinatorTest {
  private final AgentWorkflowLedger ledger = mock(AgentWorkflowLedger.class);
  private final AgentRunService runs = mock(AgentRunService.class);
  private final AgentRunLauncher launcher = mock(AgentRunLauncher.class);
  private final WorkflowArtifactParser parser = mock(WorkflowArtifactParser.class);
  private final WorkflowEvidenceService evidence = mock(WorkflowEvidenceService.class);
  private final WorkflowGitBaselineService baselines = mock(WorkflowGitBaselineService.class);
  private final AgentChatStream stream = mock(AgentChatStream.class);
  private final WorkflowConsentAutomationService consent =
      mock(WorkflowConsentAutomationService.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final Map<WorkflowArtifactKind, String> stored = new EnumMap<>(WorkflowArtifactKind.class);
  private final UUID worktreeId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final UUID workflowId = UUID.randomUUID();
  private AgentWorkflow workflow;
  private WorkflowCoordinator coordinator;
  private int maxAttempts = 3;

  @BeforeEach
  void setUp() {
    workflow = new AgentWorkflow(workflowId, UUID.randomUUID(), 0, worktreeId, "Add a feature",
        userId, Instant.now());
    coordinator = new WorkflowCoordinator(
        ledger, runs, launcher, parser, evidence, baselines, stream, mapper, consent);

    when(ledger.require(workflowId)).thenReturn(workflow);
    when(ledger.invalidateApproval(workflowId)).thenReturn(workflow);
    when(ledger.transition(eq(workflowId), any(), any(), any(), anyString(), any()))
        .thenAnswer(call -> {
          workflow.transition(call.getArgument(1), call.getArgument(2), call.getArgument(5),
              Instant.now());
          return workflow;
        });
    when(ledger.snapshot(eq(workflowId), any())).thenAnswer(call -> {
      AgentWorkflowRoleSnapshot snapshot = mock(AgentWorkflowRoleSnapshot.class);
      when(snapshot.maxAttempts()).thenReturn(maxAttempts);
      return snapshot;
    });
    when(ledger.latest(eq(workflowId), any())).thenAnswer(call ->
        artifact(call.getArgument(1), stored.getOrDefault(call.getArgument(1), "{}")));
    when(ledger.artifacts(workflowId)).thenAnswer(call -> stored.entrySet().stream()
        .map(entry -> artifact(entry.getKey(), entry.getValue())).toList());
    when(ledger.store(eq(workflowId), any(), any())).thenAnswer(call ->
        artifact(WorkflowArtifactKind.PLAN, "{}"));
    when(runs.createWorkflowStage(any(), any(), any(), anyInt(), any(), any()))
        .thenAnswer(call -> view(UUID.randomUUID(), AgentRunStatus.PENDING,
            call.getArgument(2), null, null));
    when(evidence.collect(any())).thenReturn(evidence(false, false, false));
  }

  // --- user driven transitions ----------------------------------------------------------------

  @Test
  void startLaunchesTheOrchestratorIntake() {
    coordinator.start(workflowId);

    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.ORCHESTRATOR_INTAKE);
    assertThat(workflow.status()).isEqualTo(AgentWorkflowStatus.RUNNING);
    verify(ledger).transition(eq(workflowId), eq(AgentWorkflowStage.ORCHESTRATOR_INTAKE),
        eq(AgentWorkflowStatus.RUNNING), eq(WorkflowGateOutcome.STARTED), anyString(), any());
    verify(launcher).launch(any(), eq(worktreeId), eq(userId), eq("workflow:" + workflowId));
  }

  @Test
  void aStageThatUsedItsAttemptBudgetHoldsInsteadOfLaunching() {
    when(ledger.attemptsSinceRetry(workflowId, AgentWorkflowStage.ORCHESTRATOR_INTAKE)).thenReturn(3L);

    coordinator.start(workflowId);

    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.HUMAN_HOLD);
    assertThat(workflow.holdReason()).contains("Maximum attempts");
    verify(launcher, never()).launch(any(), any(), any(), anyString());
  }

  @Test
  void aSecondAttemptIsReportedAsRetried() {
    when(ledger.attempts(workflowId, AgentWorkflowStage.ORCHESTRATOR_INTAKE)).thenReturn(1L);

    coordinator.start(workflowId);

    verify(ledger).transition(eq(workflowId), eq(AgentWorkflowStage.ORCHESTRATOR_INTAKE),
        any(), eq(WorkflowGateOutcome.RETRIED), anyString(), any());
  }

  @Test
  void planApprovalNeedsAnApprovedPlanAndStartsTheImplementer() {
    stored.put(WorkflowArtifactKind.PLAN, "{\"title\":\"t\"}");
    assertThatThrownBy(() -> coordinator.planApproved(workflowId, "alice"))
        .isInstanceOf(IllegalStateException.class);

    workflow.currentPlan(1, Instant.now());
    workflow.approvePlan(1, Instant.now());
    coordinator.planApproved(workflowId, "alice");

    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.IMPLEMENTING);
    verify(runs).createWorkflowStage(any(), any(), eq(AgentWorkflowStage.IMPLEMENTING), eq(1),
        contains("Implement this exact approved plan"), eq("alice"));
  }

  @Test
  void answeringQuestionsRevisingAndGuidanceAllReturnToPlanning() {
    stored.put(WorkflowArtifactKind.PLANNER_BRIEF, "{\"objective\":\"o\"}");
    stored.put(WorkflowArtifactKind.ANSWERS, "{\"q1\":\"a\"}");
    stored.put(WorkflowArtifactKind.USER_GUIDANCE, "{\"text\":\"be careful\"}");
    stored.put(WorkflowArtifactKind.PLAN_VERDICT, "{\"decision\":\"REVISION_REQUIRED\"}");
    stored.put(WorkflowArtifactKind.PLAN, "{\"title\":\"old\"}");

    coordinator.clarificationAnswered(workflowId, "alice");
    coordinator.planRevisionRequested(workflowId, "alice");
    coordinator.guidanceRequested(workflowId, false, "alice");

    verify(runs, org.mockito.Mockito.times(3)).createWorkflowStage(any(), any(),
        eq(AgentWorkflowStage.PLANNING), anyInt(), contains("User guidance"), eq("alice"));
    verify(ledger, org.mockito.Mockito.times(2)).invalidateApproval(workflowId);
  }

  @Test
  void guidanceDuringAnActiveRunIsQueuedForTheNextSafeBoundary() {
    coordinator.guidanceRequested(workflowId, true, "alice");

    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.PLANNING);
    assertThat(workflow.status()).isEqualTo(AgentWorkflowStatus.PENDING);
    verify(runs, never()).createWorkflowStage(any(), any(), any(), anyInt(), any(), any());
  }

  @Test
  void onlyAHeldWorkflowCanBeRetriedAndItResumesAtTheFirstMissingArtifact() {
    assertThatThrownBy(() -> coordinator.retry(workflowId, "alice"))
        .isInstanceOf(IllegalStateException.class);

    workflow.transition(AgentWorkflowStage.HUMAN_HOLD, AgentWorkflowStatus.HUMAN_HOLD, "x", Instant.now());
    coordinator.retry(workflowId, "alice");

    verify(ledger).transition(eq(workflowId), eq(AgentWorkflowStage.HUMAN_HOLD), any(),
        eq(WorkflowGateOutcome.USER_RETRIED), eq("alice"), any());
    verify(runs).createWorkflowStage(any(), any(), eq(AgentWorkflowStage.ORCHESTRATOR_INTAKE),
        anyInt(), any(), eq("alice"));
  }

  @Test
  void retryResumesAtEachLaterPhaseOnceItsArtifactsExist() {
    stored.put(WorkflowArtifactKind.PLANNER_BRIEF, "{}");
    retryFromHold();
    stored.put(WorkflowArtifactKind.PLAN, "{}");
    retryFromHold();
    workflow.currentPlan(1, Instant.now());
    workflow.approvePlan(1, Instant.now());
    retryFromHold();
    stored.put(WorkflowArtifactKind.IMPLEMENTATION_REPORT, "{}");
    retryFromHold();
    stored.put(WorkflowArtifactKind.REVIEW, "{}");
    retryFromHold();

    for (AgentWorkflowStage phase : List.of(AgentWorkflowStage.PLANNING,
        AgentWorkflowStage.ORCHESTRATOR_PLAN_REVIEW, AgentWorkflowStage.IMPLEMENTING,
        AgentWorkflowStage.ORCHESTRATOR_IMPLEMENTATION_REVIEW,
        AgentWorkflowStage.ORCHESTRATOR_FINAL_REVIEW)) {
      verify(runs).createWorkflowStage(any(), any(), eq(phase), anyInt(), any(), eq("a"));
    }
  }

  private void retryFromHold() {
    workflow.transition(AgentWorkflowStage.HUMAN_HOLD, AgentWorkflowStatus.HUMAN_HOLD, "x", Instant.now());
    coordinator.retry(workflowId, "a");
  }

  @Test
  void aHeldWorkflowCanOnlyBeAcceptedAfterPlanApprovalAndImplementation() {
    assertThatThrownBy(() -> coordinator.acceptHeld(workflowId, "alice"))
        .hasMessageContaining("held");
    workflow.transition(AgentWorkflowStage.HUMAN_HOLD, AgentWorkflowStatus.HUMAN_HOLD,
        "tests failed", Instant.now());
    assertThatThrownBy(() -> coordinator.acceptHeld(workflowId, "alice"))
        .hasMessageContaining("approved and implemented");

    workflow.currentPlan(1, Instant.now());
    workflow.approvePlan(1, Instant.now());
    stored.put(WorkflowArtifactKind.IMPLEMENTATION_REPORT, "{}");
    coordinator.acceptHeld(workflowId, "alice");

    assertThat(workflow.status()).isEqualTo(AgentWorkflowStatus.COMPLETED);
    verify(ledger).transition(eq(workflowId), eq(AgentWorkflowStage.COMPLETED), any(),
        eq(WorkflowGateOutcome.USER_APPROVED), eq("alice"),
        eq("Accepted by the user despite: tests failed"));
    verify(baselines).release(worktreeId, workflowId);
  }

  @Test
  void canAcceptHeldNeedsBoth() {
    assertThat(WorkflowCoordinator.canAcceptHeld(true, true)).isTrue();
    assertThat(WorkflowCoordinator.canAcceptHeld(true, false)).isFalse();
    assertThat(WorkflowCoordinator.canAcceptHeld(false, true)).isFalse();
  }

  @Test
  void theFinalGateOnlyAcceptsAUserApprovalWhileWaitingForIt() {
    assertThatThrownBy(() -> coordinator.finalApproved(workflowId, "alice"))
        .hasMessageContaining("final approval");

    workflow.transition(AgentWorkflowStage.ORCHESTRATOR_FINAL_REVIEW,
        AgentWorkflowStatus.WAITING_FOR_USER, "unsure", Instant.now());
    coordinator.finalApproved(workflowId, "alice");

    assertThat(workflow.status()).isEqualTo(AgentWorkflowStatus.COMPLETED);
    verify(stream).workflowStatus(any(), any());
  }

  @Test
  void aBaselineReleaseFailureDoesNotStopCompletion() {
    doThrow(new IllegalStateException("gone")).when(baselines).release(worktreeId, workflowId);
    workflow.transition(AgentWorkflowStage.ORCHESTRATOR_FINAL_REVIEW,
        AgentWorkflowStatus.WAITING_FOR_USER, "unsure", Instant.now());

    coordinator.finalApproved(workflowId, "alice");

    assertThat(workflow.status()).isEqualTo(AgentWorkflowStatus.COMPLETED);
  }

  // --- queue and cancellation -----------------------------------------------------------------

  @Test
  void startNextDoesNothingWithoutAQueuedWorkflow() {
    coordinator.startNext(worktreeId);

    verify(baselines, never()).create(any(), any());
  }

  @Test
  void startNextCreatesTheBaselineAndStartsTheWorkflow() {
    when(ledger.reserveNext(worktreeId)).thenReturn(workflow);
    when(baselines.create(worktreeId, workflowId))
        .thenReturn(new WorkflowGitBaselineService.Baseline("head", "tree"));

    coordinator.startNext(worktreeId);

    verify(ledger).baseline(workflowId, "head", "tree", "workflow:queue");
    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.ORCHESTRATOR_INTAKE);
  }

  @Test
  void aFailingBaselineHoldsTheQueuedWorkflow() {
    when(ledger.reserveNext(worktreeId)).thenReturn(workflow);
    when(baselines.create(worktreeId, workflowId)).thenThrow(new IllegalStateException());

    coordinator.startNext(worktreeId);

    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.HUMAN_HOLD);
    assertThat(workflow.holdReason()).isEqualTo("Could not prepare workflow baseline");
  }

  @Test
  void cancellationWaitsForActiveRunsAndIgnoresFinishedWorkflows() {
    when(runs.workflowRuns(workflowId)).thenReturn(List.of(
        view(UUID.randomUUID(), AgentRunStatus.RUNNING, AgentWorkflowStage.PLANNING, null, null)));
    coordinator.cancelWhenSafe(workflowId, "alice");
    assertThat(workflow.status()).isNotEqualTo(AgentWorkflowStatus.CANCELLED);

    when(runs.workflowRuns(workflowId)).thenReturn(List.of());
    coordinator.cancelWhenSafe(workflowId, "alice");
    assertThat(workflow.status()).isEqualTo(AgentWorkflowStatus.CANCELLED);
    verify(baselines).release(worktreeId, workflowId);
    verify(ledger).reserveNext(worktreeId);

    coordinator.cancelWhenSafe(workflowId, "alice");
    verify(ledger, org.mockito.Mockito.times(1)).transition(eq(workflowId),
        eq(AgentWorkflowStage.CANCELLED), any(), any(), anyString(), any());
  }

  // --- stage results --------------------------------------------------------------------------

  @Test
  void runsOutsideAWorkflowAreIgnored() {
    UUID runId = UUID.randomUUID();
    when(runs.get(runId)).thenReturn(view(runId, AgentRunStatus.COMPLETED, null, null, null));

    coordinator.stageFinished(new AgentRunTerminalEvent(runId, UUID.randomUUID()));

    verify(parser, never()).parse(any(), any());
  }

  @Test
  void aFailedStageRunIsRecordedAndRetried() {
    stored.put(WorkflowArtifactKind.PLANNER_BRIEF, "{}");
    UUID runId = finished(AgentWorkflowStage.PLANNING, AgentRunStatus.FAILED, null, "boom");
    when(ledger.recordStageFailure(eq(workflowId), eq(runId), anyString(), anyString()))
        .thenReturn(workflow);

    coordinator.stageFinished(new AgentRunTerminalEvent(runId, UUID.randomUUID()));

    verify(ledger).recordStageFailure(eq(workflowId), eq(runId),
        eq("Stage model request failed: boom"), anyString());
    verify(runs).createWorkflowStage(any(), any(), eq(AgentWorkflowStage.PLANNING), anyInt(), any(), any());
  }

  @Test
  void aRunWithoutAnErrorMessageSaysHowItEnded() {
    stored.put(WorkflowArtifactKind.PLANNER_BRIEF, "{}");
    UUID runId = finished(AgentWorkflowStage.PLANNING, AgentRunStatus.CANCELLED, null, null);
    when(ledger.recordStageFailure(any(), any(), anyString(), anyString())).thenReturn(workflow);

    coordinator.stageFinished(new AgentRunTerminalEvent(runId, UUID.randomUUID()));

    verify(ledger).recordStageFailure(any(), eq(runId), eq("Stage run ended with CANCELLED"), anyString());
  }

  @Test
  void aStageFailureHoldsOnceTheAttemptsAreUsedUp() {
    UUID runId = finished(AgentWorkflowStage.PLANNING, AgentRunStatus.FAILED, null, "boom");
    when(ledger.recordStageFailure(any(), any(), anyString(), anyString())).thenReturn(workflow);
    when(ledger.attemptsSinceRetry(workflowId, AgentWorkflowStage.PLANNING)).thenReturn(3L);

    coordinator.stageFinished(new AgentRunTerminalEvent(runId, UUID.randomUUID()));

    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.HUMAN_HOLD);
    assertThat(workflow.holdReason()).isEqualTo("Stage model request failed: boom");
  }

  @Test
  void anUnparsableResponseTriggersAFormatRepairAndThenAHold() {
    stored.put(WorkflowArtifactKind.PLANNER_BRIEF, "{}");
    UUID runId = finished(AgentWorkflowStage.PLANNING, AgentRunStatus.COMPLETED, "not json", null);
    when(parser.parse(AgentWorkflowStage.PLANNING, "not json"))
        .thenThrow(new IllegalArgumentException("no JSON object"));
    when(ledger.recordStageFailure(any(), any(), anyString(), anyString())).thenReturn(workflow);

    coordinator.stageFinished(new AgentRunTerminalEvent(runId, UUID.randomUUID()));
    verify(runs).createWorkflowStage(any(), any(), eq(AgentWorkflowStage.PLANNING), anyInt(),
        contains("FORMAT REPAIR REQUIRED"), any());

    when(ledger.attemptsSinceRetry(workflowId, AgentWorkflowStage.PLANNING)).thenReturn(3L);
    coordinator.stageFinished(new AgentRunTerminalEvent(runId, UUID.randomUUID()));
    assertThat(workflow.holdReason()).contains("could not produce the required structured response");
  }

  @Test
  void aResultThatAlreadyHasAnArtifactIsNotProcessedTwice() {
    UUID runId = finished(AgentWorkflowStage.PLANNING, AgentRunStatus.FAILED, null, "boom");
    when(ledger.artifactForRun(runId)).thenReturn(artifact(WorkflowArtifactKind.STAGE_FAILURE, "{}"));

    coordinator.stageFinished(new AgentRunTerminalEvent(runId, UUID.randomUUID()));

    verify(ledger, never()).recordStageFailure(any(), any(), any(), any());
  }

  @Test
  void aResultForAnEarlierPhaseOnlyResumesAPendingWorkflow() {
    stored.put(WorkflowArtifactKind.PLANNER_BRIEF, "{}");
    workflow.transition(AgentWorkflowStage.PLANNING, AgentWorkflowStatus.PENDING, null, Instant.now());
    UUID runId = finished(AgentWorkflowStage.ORCHESTRATOR_INTAKE, AgentRunStatus.COMPLETED, "{}", null);

    coordinator.stageFinished(new AgentRunTerminalEvent(runId, UUID.randomUUID()));

    verify(runs).createWorkflowStage(any(), any(), eq(AgentWorkflowStage.PLANNING), anyInt(), any(),
        eq("workflow:safe-boundary"));
    verify(parser, never()).parse(any(), any());
  }

  @Test
  void aResultForACancellingWorkflowCompletesTheCancellation() {
    workflow.transition(AgentWorkflowStage.PLANNING, AgentWorkflowStatus.CANCELLING, null, Instant.now());
    UUID runId = finished(AgentWorkflowStage.PLANNING, AgentRunStatus.COMPLETED, "{}", null);

    coordinator.stageFinished(new AgentRunTerminalEvent(runId, UUID.randomUUID()));

    assertThat(workflow.status()).isEqualTo(AgentWorkflowStatus.CANCELLED);
  }

  @Test
  void anUnexpectedErrorWhileCoordinatingFailsClosedIntoAHold() {
    UUID runId = finished(AgentWorkflowStage.PLANNING, AgentRunStatus.COMPLETED, "{}", null);
    when(parser.parse(any(), any())).thenReturn(parsed(WorkflowArtifactKind.PLAN, "{}"));
    when(ledger.store(any(), any(), any())).thenThrow(new IllegalStateException("db down"));

    coordinator.stageFinished(new AgentRunTerminalEvent(runId, UUID.randomUUID()));

    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.HUMAN_HOLD);
    assertThat(workflow.holdReason()).isEqualTo("db down");
  }

  @Test
  void failingClosedHasADefaultReasonAndLeavesFinishedOrHeldWorkflowsAlone() {
    UUID runId = finished(AgentWorkflowStage.PLANNING, AgentRunStatus.COMPLETED, "{}", null);
    when(parser.parse(any(), any())).thenThrow(new IllegalStateException());
    when(ledger.recordStageFailure(any(), any(), any(), any())).thenThrow(new IllegalStateException());

    coordinator.stageFinished(new AgentRunTerminalEvent(runId, UUID.randomUUID()));
    assertThat(workflow.holdReason()).isEqualTo("Workflow coordination failed safely");

    coordinator.stageFinished(new AgentRunTerminalEvent(runId, UUID.randomUUID()));
    verify(ledger, org.mockito.Mockito.times(1)).transition(eq(workflowId),
        eq(AgentWorkflowStage.HUMAN_HOLD), any(), eq(WorkflowGateOutcome.HOLD), anyString(), any());
  }

  // --- stage routing --------------------------------------------------------------------------

  @Test
  void intakeIsFollowedByPlanning() {
    stored.put(WorkflowArtifactKind.PLANNER_BRIEF, "{}");
    complete(AgentWorkflowStage.ORCHESTRATOR_INTAKE, WorkflowArtifactKind.PLANNER_BRIEF, "{}");

    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.PLANNING);
    verify(stream).workflowArtifact(any(), eq(workflowId), any());
  }

  @Test
  void planningEitherAsksQuestionsOrGoesToPlanReview() {
    complete(AgentWorkflowStage.PLANNING, WorkflowArtifactKind.QUESTIONS, "{}");
    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.WAITING_FOR_CLARIFICATION);
    assertThat(workflow.status()).isEqualTo(AgentWorkflowStatus.WAITING_FOR_USER);

    complete(AgentWorkflowStage.PLANNING, WorkflowArtifactKind.PLAN, "{}");
    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.ORCHESTRATOR_PLAN_REVIEW);
  }

  @Test
  void thePlanVerdictRoutesEveryDecision() {
    complete(AgentWorkflowStage.ORCHESTRATOR_PLAN_REVIEW, WorkflowArtifactKind.PLAN_VERDICT,
        "{\"decision\":\"PASS\"}");
    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.WAITING_FOR_PLAN_APPROVAL);
    assertThat(workflow.holdReason()).isEqualTo("Plan passed orchestration review");

    complete(AgentWorkflowStage.ORCHESTRATOR_PLAN_REVIEW, WorkflowArtifactKind.PLAN_VERDICT,
        "{\"decision\":\"PASS_WITH_WARNINGS\",\"reason\":\"risky\"}");
    assertThat(workflow.holdReason()).isEqualTo("Plan is ready for user review with warnings: risky");

    complete(AgentWorkflowStage.ORCHESTRATOR_PLAN_REVIEW, WorkflowArtifactKind.PLAN_VERDICT,
        "{\"decision\":\"REVISION_REQUIRED\",\"reason\":\"fix\"}");
    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.PLANNING);

    complete(AgentWorkflowStage.ORCHESTRATOR_PLAN_REVIEW, WorkflowArtifactKind.PLAN_VERDICT,
        "{\"decision\":\"HOLD\",\"hardBlock\":true,\"reason\":\"destructive\"}");
    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.HUMAN_HOLD);
    assertThat(workflow.holdReason()).isEqualTo("destructive");

    complete(AgentWorkflowStage.ORCHESTRATOR_PLAN_REVIEW, WorkflowArtifactKind.PLAN_VERDICT,
        "{\"decision\":\"HOLD\",\"reason\":\"unsure\"}");
    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.WAITING_FOR_PLAN_APPROVAL);
  }

  @Test
  void implementationWithOpenApprovalActionsHolds() {
    stored.put(WorkflowArtifactKind.PLAN, "{}");
    when(evidence.collect(any())).thenReturn(evidence(true, false, false));

    complete(AgentWorkflowStage.IMPLEMENTING, WorkflowArtifactKind.IMPLEMENTATION_REPORT, "{}");

    verify(consent).settleOpenActions(workflowId);
    assertThat(workflow.holdReason()).isEqualTo("Implementation left approval actions open");
    verify(ledger).storeUserArtifact(eq(workflowId), eq(WorkflowArtifactKind.TASK_DIFF), any(),
        eq("No workflow-owned file changes."));
  }

  @Test
  void aConflictOrFailedTestSendsTheImplementationBackWithAReason() {
    stored.put(WorkflowArtifactKind.PLAN, "{}");
    when(evidence.collect(any())).thenReturn(evidence(false, true, false));
    complete(AgentWorkflowStage.IMPLEMENTING, WorkflowArtifactKind.IMPLEMENTATION_REPORT, "{}");
    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.IMPLEMENTING);

    when(evidence.collect(any())).thenReturn(evidence(false, false, true));
    complete(AgentWorkflowStage.IMPLEMENTING, WorkflowArtifactKind.IMPLEMENTATION_REPORT, "{}");
    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.IMPLEMENTING);
  }

  @Test
  void cleanImplementationEvidenceGoesToTheImplementationReview() {
    stored.put(WorkflowArtifactKind.PLAN, "{}");
    stored.put(WorkflowArtifactKind.IMPLEMENTATION_REPORT, "{\"summary\":\"done\"}");
    when(evidence.collect(any())).thenReturn(new WorkflowEvidenceService.Evidence(
        List.of("A.java"), "diff --git", Map.of("A.java", "h"), List.of(), List.of(), false, false, false));

    complete(AgentWorkflowStage.IMPLEMENTING, WorkflowArtifactKind.IMPLEMENTATION_REPORT, "{}");

    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.ORCHESTRATOR_IMPLEMENTATION_REVIEW);
    verify(ledger).storeUserArtifact(eq(workflowId), eq(WorkflowArtifactKind.TASK_DIFF), any(),
        contains("diff --git"));
  }

  @Test
  void theImplementationVerdictRoutesPassRevisionAndHold() {
    stored.put(WorkflowArtifactKind.PLAN, "{}");
    stored.put(WorkflowArtifactKind.TASK_DIFF, "{\"paths\":[\"A.java\"],\"tests\":[{\"id\":\"t1\","
        + "\"status\":\"PASSED\",\"command\":\"mvn test\"}]}");
    complete(AgentWorkflowStage.ORCHESTRATOR_IMPLEMENTATION_REVIEW,
        WorkflowArtifactKind.IMPLEMENTATION_VERDICT, "{\"decision\":\"PASS\"}");
    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.REVIEWING);

    complete(AgentWorkflowStage.ORCHESTRATOR_IMPLEMENTATION_REVIEW,
        WorkflowArtifactKind.IMPLEMENTATION_VERDICT,
        "{\"decision\":\"REVISION_REQUIRED\",\"reason\":\"more\"}");
    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.IMPLEMENTING);

    complete(AgentWorkflowStage.ORCHESTRATOR_IMPLEMENTATION_REVIEW,
        WorkflowArtifactKind.IMPLEMENTATION_VERDICT, "{\"decision\":\"HOLD\",\"reason\":\"stop\"}");
    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.HUMAN_HOLD);
  }

  @Test
  void theReviewerIsFollowedByTheFinalReview() {
    stored.put(WorkflowArtifactKind.PLAN, "{}");
    stored.put(WorkflowArtifactKind.REVIEW, "{\"decision\":\"APPROVED\"}");
    stored.put(WorkflowArtifactKind.TASK_DIFF, "{\"paths\":[\"A.java\"],\"tests\":[]}");

    complete(AgentWorkflowStage.REVIEWING, WorkflowArtifactKind.REVIEW, "{}");

    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.ORCHESTRATOR_FINAL_REVIEW);
  }

  @Test
  void aStageThatShouldNeverFinishHoldsTheWorkflow() {
    complete(AgentWorkflowStage.WAITING_FOR_PLAN_APPROVAL, WorkflowArtifactKind.PLAN, "{}");

    assertThat(workflow.holdReason()).isEqualTo("Unexpected stage completion");
  }

  @Test
  void aCleanFinalVerdictCompletesTheWorkflow() {
    finalReview("APPROVED", "{\"decision\":\"PASS\",\"reason\":\"all good\"}", List.of(verified()));

    assertThat(workflow.status()).isEqualTo(AgentWorkflowStatus.COMPLETED);
    verify(ledger).transition(eq(workflowId), eq(AgentWorkflowStage.COMPLETED), any(),
        eq(WorkflowGateOutcome.PASS), eq("orchestrator"), eq("all good"));
  }

  @Test
  void anApprovedReviewWithUnconfirmedGoalsAsksTheUser() {
    finalReview("APPROVED", "{\"decision\":\"PASS\"}", List.of(unverified()));

    assertThat(workflow.status()).isEqualTo(AgentWorkflowStatus.WAITING_FOR_USER);
    assertThat(workflow.holdReason()).contains("1 of 1 acceptance criteria", "AC1");
  }

  @Test
  void anUncertainFinalGateAsksTheUserToDecide() {
    finalReview("APPROVED", "{\"decision\":\"HOLD\",\"reason\":\"unsure\"}", List.of(verified()));

    assertThat(workflow.status()).isEqualTo(AgentWorkflowStatus.WAITING_FOR_USER);
    assertThat(workflow.holdReason()).contains("could not confirm on its own");
  }

  @Test
  void aMissingPassingTestAsksTheUser() {
    finalReview("APPROVED", "{\"decision\":\"REVISION_REQUIRED\"}", List.of(verified()));

    assertThat(workflow.status()).isEqualTo(AgentWorkflowStatus.WAITING_FOR_USER);
    assertThat(workflow.holdReason()).contains("no executed passing test");
  }

  @Test
  void requestedChangesSendTheWorkflowBackToImplementation() {
    finalReview("CHANGES_REQUIRED", "{\"decision\":\"PASS\"}", List.of(verified()));

    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.IMPLEMENTING);
  }

  @Test
  void aFailedReviewWithoutAClearReasonHolds() {
    finalReview("REJECTED", "{\"decision\":\"HOLD\"}", List.of(verified()));

    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.HUMAN_HOLD);
    assertThat(workflow.holdReason()).isEqualTo("Final review could not pass");
  }

  @Test
  void passedTestEvidenceLetsTheFinalGateSucceedDespiteAnApprovedReview() {
    when(evidence.collect(any())).thenReturn(new WorkflowEvidenceService.Evidence(
        List.of(), "", Map.of(), List.of(), List.of(test(TestRunStatus.PASSED)), false, false, false));
    finalReview("APPROVED", "{\"decision\":\"PASS\"}", List.of(verified()));

    assertThat(workflow.status()).isEqualTo(AgentWorkflowStatus.COMPLETED);
  }

  @Test
  void unclearOutcomesAreOnlyThoseWithoutAConcreteDefect() {
    assertThat(WorkflowCoordinator.unclear("HOLD", "APPROVED")).isTrue();
    assertThat(WorkflowCoordinator.unclear("PASS", "HOLD")).isFalse();
    assertThat(WorkflowCoordinator.unclear("REVISION_REQUIRED", "APPROVED")).isFalse();
    assertThat(WorkflowCoordinator.unclear("HOLD", "CHANGES_REQUIRED")).isFalse();
    assertThat(WorkflowCoordinator.unclear("HOLD", "REJECTED")).isFalse();
  }

  // --- recovery -------------------------------------------------------------------------------

  @Test
  void recoveryLeavesWaitingHeldAndFinishedWorkflowsAlone() {
    workflow.transition(AgentWorkflowStage.WAITING_FOR_PLAN_APPROVAL,
        AgentWorkflowStatus.WAITING_FOR_USER, null, Instant.now());
    when(ledger.recoverable()).thenReturn(List.of(workflow));

    coordinator.recover();

    verify(runs, never()).createWorkflowStage(any(), any(), any(), anyInt(), any(), any());
  }

  @Test
  void recoveryCompletesCancellationOfCancellingWorkflows() {
    workflow.transition(AgentWorkflowStage.PLANNING, AgentWorkflowStatus.CANCELLING, null, Instant.now());
    when(ledger.recoverable()).thenReturn(List.of(workflow));

    coordinator.recover();

    assertThat(workflow.status()).isEqualTo(AgentWorkflowStatus.CANCELLED);
  }

  @Test
  void recoveryStartsQueuedWorkflowsWithOrWithoutAReservation() {
    when(ledger.recoverable()).thenReturn(List.of(workflow));
    coordinator.recover();
    verify(ledger).reserveNext(worktreeId);

    when(ledger.hasReservation(workflowId)).thenReturn(true);
    when(baselines.create(worktreeId, workflowId))
        .thenReturn(new WorkflowGitBaselineService.Baseline("h", "t"));
    coordinator.recover();
    verify(ledger).baseline(workflowId, "h", "t", "workflow:recovery");
    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.ORCHESTRATOR_INTAKE);
  }

  @Test
  void aReservedWorkflowWhoseBaselineFailsIsHeldDuringRecovery() {
    when(ledger.recoverable()).thenReturn(List.of(workflow));
    when(ledger.hasReservation(workflowId)).thenReturn(true);
    when(baselines.create(worktreeId, workflowId)).thenThrow(new IllegalStateException("no git"));

    coordinator.recover();

    assertThat(workflow.holdReason()).isEqualTo("no git");
  }

  @Test
  void recoveryRelaunchesAPhaseThatNeverGotARun() {
    workflow.transition(AgentWorkflowStage.ORCHESTRATOR_INTAKE, AgentWorkflowStatus.RUNNING, null, Instant.now());
    when(ledger.recoverable()).thenReturn(List.of(workflow));
    when(runs.workflowRuns(workflowId)).thenReturn(List.of());

    coordinator.recover();

    verify(runs).createWorkflowStage(any(), any(), eq(AgentWorkflowStage.ORCHESTRATOR_INTAKE),
        anyInt(), any(), eq("workflow:recovery"));
  }

  @Test
  void recoveryRelaunchesAPendingRunAndProcessesAFinishedOne() {
    stored.put(WorkflowArtifactKind.PLANNER_BRIEF, "{}");
    workflow.transition(AgentWorkflowStage.PLANNING, AgentWorkflowStatus.RUNNING, null, Instant.now());
    when(ledger.recoverable()).thenReturn(List.of(workflow));
    AgentRunView pending = view(UUID.randomUUID(), AgentRunStatus.PENDING, AgentWorkflowStage.PLANNING, null, null);
    when(runs.workflowRuns(workflowId)).thenReturn(List.of(pending));

    coordinator.recover();
    verify(launcher).launch(pending.id(), pending.worktreeId(), pending.createdBy(),
        "workflow-recovery:" + workflowId);

    AgentRunView done = view(UUID.randomUUID(), AgentRunStatus.COMPLETED, AgentWorkflowStage.PLANNING, "{}", null);
    when(runs.workflowRuns(workflowId)).thenReturn(List.of(done));
    when(parser.parse(AgentWorkflowStage.PLANNING, "{}")).thenReturn(parsed(WorkflowArtifactKind.PLAN, "{}"));
    coordinator.recover();
    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.ORCHESTRATOR_PLAN_REVIEW);
  }

  @Test
  void recoveryRetriesAPendingWorkflowWhoseLastRunFailed() {
    stored.put(WorkflowArtifactKind.PLANNER_BRIEF, "{}");
    workflow.transition(AgentWorkflowStage.PLANNING, AgentWorkflowStatus.PENDING, null, Instant.now());
    when(ledger.recoverable()).thenReturn(List.of(workflow));
    AgentRunView failed = view(UUID.randomUUID(), AgentRunStatus.FAILED, AgentWorkflowStage.PLANNING, null, "x");
    when(runs.workflowRuns(workflowId)).thenReturn(List.of(failed));
    when(ledger.artifactForRun(failed.id()))
        .thenReturn(artifact(WorkflowArtifactKind.STAGE_FAILURE, "{}"));

    coordinator.recover();

    verify(runs).createWorkflowStage(any(), any(), eq(AgentWorkflowStage.PLANNING), anyInt(), any(),
        eq("workflow:recovery"));
  }

  @Test
  void recoveryFailsClosedWhenOneWorkflowBreaks() {
    workflow.transition(AgentWorkflowStage.PLANNING, AgentWorkflowStatus.RUNNING, null, Instant.now());
    when(ledger.recoverable()).thenReturn(List.of(workflow));
    when(runs.workflowRuns(workflowId)).thenThrow(new IllegalStateException("broken"));

    coordinator.recover();

    assertThat(workflow.currentStage()).isEqualTo(AgentWorkflowStage.HUMAN_HOLD);
    assertThat(workflow.holdReason()).isEqualTo("broken");
  }

  // --- helpers --------------------------------------------------------------------------------

  /** Runs the stage result of {@code phase} through the coordinator, as the executor would. */
  private void complete(AgentWorkflowStage phase, WorkflowArtifactKind kind, String json) {
    workflow.transition(phase, AgentWorkflowStatus.RUNNING, null, Instant.now());
    UUID runId = finished(phase, AgentRunStatus.COMPLETED, json, null);
    when(parser.parse(phase, json)).thenReturn(parsed(kind, json));
    coordinator.stageFinished(new AgentRunTerminalEvent(runId, UUID.randomUUID()));
  }

  private void finalReview(String reviewDecision, String verdictJson, List<GoalVerificationView> goals) {
    stored.put(WorkflowArtifactKind.PLAN, "{}");
    stored.put(WorkflowArtifactKind.TASK_DIFF, "{\"paths\":[],\"tests\":[]}");
    stored.put(WorkflowArtifactKind.REVIEW, "{\"decision\":\"" + reviewDecision + "\"}");
    when(ledger.goals(workflowId)).thenReturn(goals);
    complete(AgentWorkflowStage.ORCHESTRATOR_FINAL_REVIEW, WorkflowArtifactKind.FINAL_VERDICT, verdictJson);
  }

  private UUID finished(AgentWorkflowStage phase, AgentRunStatus status, String output, String error) {
    if (workflow.currentStage() == AgentWorkflowStage.QUEUED) {
      workflow.transition(phase, AgentWorkflowStatus.RUNNING, null, Instant.now());
    }
    UUID runId = UUID.randomUUID();
    when(runs.get(runId)).thenReturn(view(runId, status, phase, output, error));
    return runId;
  }

  private AgentRunView view(UUID id, AgentRunStatus status, AgentWorkflowStage phase, String output, String error) {
    return new AgentRunView(id, worktreeId, ModelProvider.OPENAI, null, "model", "prompt", status,
        10, 1000, null, null, 0, output, error, userId, Instant.now(), null, null, List.of(),
        UUID.randomUUID(), 0, 1000, null, phase == null ? null : workflowId,
        phase == null ? null : AgentWorkflowRole.PLANNER, phase, 1, 0, 0, null);
  }

  private WorkflowArtifactParser.Parsed parsed(WorkflowArtifactKind kind, String json) {
    JsonNode value = mapper.readTree(json);
    return new WorkflowArtifactParser.Parsed(kind, json, "markdown", value);
  }

  private AgentWorkflowArtifact artifact(WorkflowArtifactKind kind, String json) {
    return new AgentWorkflowArtifact(UUID.randomUUID(), workflowId, null, kind, 1, json, "md", Instant.now());
  }

  private static WorkflowEvidenceService.Evidence evidence(boolean open, boolean conflict, boolean failed) {
    return new WorkflowEvidenceService.Evidence(
        new ArrayList<>(), "", Map.of(), List.of(), List.of(), open, conflict, failed);
  }

  private static GoalVerificationView verified() {
    return new GoalVerificationView("AC1", "works", GoalStatus.VERIFIED, null, List.of(), List.of());
  }

  private static GoalVerificationView unverified() {
    return new GoalVerificationView("AC1", "works", GoalStatus.UNVERIFIED, null, List.of(), List.of());
  }

  private static TestRunView test(TestRunStatus status) {
    return new TestRunView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "mvn test",
        status, 0, "", false, null, null, null, null, null, null, null, null, null);
  }
}
