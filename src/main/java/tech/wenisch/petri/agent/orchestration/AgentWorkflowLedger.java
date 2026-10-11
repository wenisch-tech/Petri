package tech.wenisch.petri.agent.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.agent.change.ChangeProposalService;
import tech.wenisch.petri.agent.run.TestRunService;

/** All short transactional mutations for the workflow state machine. */
@Service
class AgentWorkflowLedger {
  private final AgentWorkflowRepository workflows;
  private final AgentWorkflowArtifactRepository artifacts;
  private final AgentWorkflowTransitionRepository transitions;
  private final AgentWorkflowRoleSnapshotRepository snapshots;
  private final WorktreeExecutionReservationRepository reservations;
  private final AgentChatRepository chats;
  private final AgentRunService runs;
  private final OrchestrationProfileService profiles;
  private final AuditLogService audit;
  private final ChangeProposalService changes;
  private final TestRunService tests;

  AgentWorkflowLedger(
      AgentWorkflowRepository workflows,
      AgentWorkflowArtifactRepository artifacts,
      AgentWorkflowTransitionRepository transitions,
      AgentWorkflowRoleSnapshotRepository snapshots,
      WorktreeExecutionReservationRepository reservations,
      AgentChatRepository chats,
      AgentRunService runs,
      OrchestrationProfileService profiles,
      AuditLogService audit,
      ChangeProposalService changes,
      TestRunService tests) {
    this.workflows = workflows; this.artifacts = artifacts; this.transitions = transitions;
    this.snapshots = snapshots; this.reservations = reservations; this.chats = chats;
    this.runs = runs; this.profiles = profiles; this.audit = audit;
    this.changes = changes; this.tests = tests;
  }

  @Transactional
  AgentWorkflow create(
      UUID workflowId, UUID requestedChatId, UUID parentChatId, UUID worktreeId, UUID projectId,
      String prompt, UUID creatorId, String actor, List<SaveOrchestrationRoleProfile> roleOverrides) {
    Instant now = Instant.now();
    AgentChat chat;
    if (requestedChatId != null) {
      chat = chats.findForUpdateById(requestedChatId)
          .orElseThrow(() -> new IllegalArgumentException("Unknown chat"));
      if (!chat.createdBy().equals(creatorId)) throw new IllegalArgumentException("Chat belongs to another creator");
      if (!chat.worktreeId().equals(worktreeId)) throw new IllegalArgumentException("Chat belongs to another worktree");
      if (chat.executionMode() != AgentChatExecutionMode.ORCHESTRATED) {
        throw new IllegalStateException("Direct chats cannot contain orchestrated workflows");
      }
    } else {
      chat = chats.save(new AgentChat(UUID.randomUUID(), worktreeId, title(prompt), creatorId,
          AgentChatExecutionMode.ORCHESTRATED, parentChatId, null, now));
    }
    WorktreeExecutionReservation existingReservation = reservations.findById(worktreeId).orElse(null);
    if (existingReservation != null) {
      AgentWorkflow active = workflows.findById(existingReservation.workflowId())
          .orElseThrow(() -> new IllegalStateException("Worktree reservation is inconsistent"));
      if (requestedChatId == null || !active.chatId().equals(chat.id())) {
        throw new IllegalStateException("This worktree is reserved by another workflow");
      }
    }
    AgentWorkflow workflow = workflows.save(new AgentWorkflow(
        workflowId, chat.id(), chat.nextMessageIndex(now), worktreeId, prompt, creatorId, now));
    if (existingReservation == null) {
      reservations.save(new WorktreeExecutionReservation(worktreeId, workflow.id(), creatorId, now));
    }
    profiles.snapshot(workflow.id(), projectId, creatorId, roleOverrides);
    if (parentChatId != null) chat.linkFork(workflow.id(), now);
    transitionRecord(workflow, null, AgentWorkflowStage.QUEUED, WorkflowGateOutcome.STARTED,
        actor, "Workflow created", now);
    audit.record(actor, "AGENT_WORKFLOW_CREATED", "AGENT_WORKFLOW", workflow.id(),
        "Created gated agent workflow for worktree " + worktreeId);
    return workflow;
  }

  @Transactional
  AgentWorkflow baseline(UUID workflowId, String head, String tree, String actor) {
    AgentWorkflow workflow = locked(workflowId);
    workflow.baseline(head, tree, Instant.now());
    audit.record(actor, "AGENT_WORKFLOW_BASELINE_CREATED", "AGENT_WORKFLOW", workflow.id(),
        "Captured immutable workflow baseline");
    return workflow;
  }

  @Transactional
  AgentWorkflow transition(UUID workflowId, AgentWorkflowStage target, AgentWorkflowStatus status,
      WorkflowGateOutcome outcome, String actor, String reason) {
    AgentWorkflow workflow = locked(workflowId);
    AgentWorkflowStage from = workflow.currentStage();
    Instant now = Instant.now();
    workflow.transition(target, status, bounded(reason), now);
    transitionRecord(workflow, from, target, outcome, actor, bounded(reason), now);
    audit.record(actor, "AGENT_WORKFLOW_TRANSITION", "AGENT_WORKFLOW", workflow.id(),
        "Transitioned workflow from " + from + " to " + target + " with " + outcome);
    if (workflow.terminal()) reservations.findByWorkflowId(workflow.id()).ifPresent(reservations::delete);
    return workflow;
  }

  @Transactional
  AgentWorkflowArtifact store(
      UUID workflowId, UUID runId, WorkflowArtifactParser.Parsed parsed) {
    AgentWorkflow workflow = locked(workflowId);
    AgentWorkflowArtifact existing = artifacts.findByAgentRunId(runId).orElse(null);
    if (existing != null) return existing;
    int revision = nextRevision(workflowId, parsed.kind());
    AgentWorkflowArtifact artifact = artifacts.save(new AgentWorkflowArtifact(
        UUID.randomUUID(), workflowId, runId, parsed.kind(), revision, parsed.json(),
        parsed.markdown(), Instant.now()));
    if (parsed.kind() == WorkflowArtifactKind.PLAN) workflow.currentPlan(revision, Instant.now());
    return artifact;
  }

  @Transactional
  AgentWorkflow recordStageFailure(UUID workflowId, UUID runId, String reason, String actor) {
    AgentWorkflow workflow = locked(workflowId);
    if (artifacts.findByAgentRunId(runId).isEmpty()) {
      artifacts.save(new AgentWorkflowArtifact(UUID.randomUUID(), workflowId, runId,
          WorkflowArtifactKind.STAGE_FAILURE,
          nextRevision(workflowId, WorkflowArtifactKind.STAGE_FAILURE),
          "{\"error\":\"" + jsonEscape(bounded(reason)) + "\"}", bounded(reason), Instant.now()));
    }
    AgentWorkflowStage stage = workflow.currentStage();
    if (workflow.status() == AgentWorkflowStatus.RUNNING) {
      Instant now = Instant.now();
      workflow.transition(stage, AgentWorkflowStatus.PENDING, bounded(reason), now);
      transitionRecord(workflow, stage, stage, WorkflowGateOutcome.RETRIED, actor,
          bounded(reason), now);
    }
    return workflow;
  }

  @Transactional
  AgentWorkflowArtifact storeUserArtifact(
      UUID workflowId, WorkflowArtifactKind kind, String json, String markdown) {
    locked(workflowId);
    return artifacts.save(new AgentWorkflowArtifact(UUID.randomUUID(), workflowId, null, kind,
        nextRevision(workflowId, kind), json, markdown, Instant.now()));
  }

  @Transactional
  AgentWorkflow approvePlan(UUID workflowId, int revision, UUID actorId, String actor) {
    AgentWorkflow workflow = lockedOwned(workflowId, actorId);
    if (workflow.currentStage() != AgentWorkflowStage.WAITING_FOR_PLAN_APPROVAL) {
      throw new IllegalStateException("Workflow is not waiting for plan approval");
    }
    workflow.approvePlan(revision, Instant.now());
    transitionRecord(workflow, workflow.currentStage(), workflow.currentStage(),
        WorkflowGateOutcome.USER_APPROVED, actor, "Approved plan revision " + revision,
        Instant.now());
    audit.record(actor, "AGENT_WORKFLOW_PLAN_APPROVED", "AGENT_WORKFLOW", workflow.id(),
        "Approved workflow plan revision " + revision);
    return workflow;
  }

  @Transactional
  AgentWorkflow invalidateApproval(UUID workflowId) {
    AgentWorkflow workflow = locked(workflowId);
    workflow.invalidateApproval(Instant.now());
    return workflow;
  }

  @Transactional(readOnly = true)
  AgentWorkflow require(UUID id) {
    return workflows.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown workflow"));
  }

  @Transactional(readOnly = true)
  AgentWorkflow requireOwned(UUID id, UUID actorId) {
    AgentWorkflow value = require(id);
    if (!value.createdBy().equals(actorId)) throw new IllegalArgumentException("Unknown workflow");
    return value;
  }

  @Transactional(readOnly = true)
  List<AgentWorkflow> chatWorkflows(UUID chatId) {
    return workflows.findByChatIdOrderByChatMessageIndexAsc(chatId);
  }

  @Transactional(readOnly = true)
  List<AgentWorkflow> recoverable() {
    return workflows.findByStatusInOrderByCreatedAtAsc(
        List.of(AgentWorkflowStatus.PENDING, AgentWorkflowStatus.RUNNING,
            AgentWorkflowStatus.CANCELLING));
  }

  @Transactional(readOnly = true)
  boolean hasReservation(UUID workflowId) {
    return reservations.findByWorkflowId(workflowId).isPresent();
  }

  @Transactional
  AgentWorkflow reserveNext(UUID worktreeId) {
    if (reservations.existsById(worktreeId)) return null;
    AgentWorkflow next = workflows
        .findByExecutionWorktreeIdAndStatusAndCurrentStageOrderByCreatedAtAsc(
            worktreeId, AgentWorkflowStatus.PENDING, AgentWorkflowStage.QUEUED)
        .stream().findFirst().orElse(null);
    if (next == null) return null;
    reservations.save(new WorktreeExecutionReservation(
        worktreeId, next.id(), next.createdBy(), Instant.now()));
    return next;
  }

  @Transactional(readOnly = true)
  AgentWorkflowRoleSnapshot snapshot(UUID workflowId, AgentWorkflowRole role) {
    return snapshots.findByWorkflowIdAndRole(workflowId, role)
        .orElseThrow(() -> new IllegalStateException("Workflow role snapshot is missing"));
  }

  @Transactional(readOnly = true)
  List<AgentWorkflowArtifact> artifacts(UUID workflowId) {
    return artifacts.findByWorkflowIdOrderByCreatedAtAsc(workflowId);
  }

  @Transactional(readOnly = true)
  AgentWorkflowArtifact artifactForRun(UUID runId) {
    return artifacts.findByAgentRunId(runId).orElse(null);
  }

  @Transactional(readOnly = true)
  AgentWorkflowArtifact latest(UUID workflowId, WorkflowArtifactKind kind) {
    return artifacts.findFirstByWorkflowIdAndKindOrderByRevisionDesc(workflowId, kind)
        .orElseThrow(() -> new IllegalStateException("Required workflow artifact is missing: " + kind));
  }

  /** Attempts of a phase since the last manual retry, which grants a fresh attempt budget. */
  @Transactional(readOnly = true)
  long attemptsSinceRetry(UUID workflowId, AgentWorkflowStage phase) {
    Instant reset = transitions.findByWorkflowIdOrderByCreatedAtAsc(workflowId).stream()
        .filter(value -> value.outcome() == WorkflowGateOutcome.USER_RETRIED)
        .map(AgentWorkflowTransition::createdAt).reduce((first, second) -> second).orElse(null);
    return runs.workflowRuns(workflowId).stream()
        .filter(run -> run.workflowPhase() == phase)
        .filter(run -> reset == null || !run.createdAt().isBefore(reset)).count();
  }

  @Transactional(readOnly = true)
  long attempts(UUID workflowId, AgentWorkflowStage phase) {
    return runs.workflowRuns(workflowId).stream().filter(run -> run.workflowPhase() == phase).count();
  }

  @Transactional(readOnly = true)
  AgentWorkflowView view(UUID workflowId, UUID actorId) {
    AgentWorkflow workflow = requireOwned(workflowId, actorId);
    return view(workflow);
  }

  AgentWorkflowView view(AgentWorkflow workflow) {
    List<AgentRunView> stageRuns = runs.workflowRuns(workflow.id());
    List<AgentWorkflowArtifactView> artifactViews = artifacts(workflow.id()).stream()
        .map(value -> new AgentWorkflowArtifactView(value.id(), value.agentRunId(), value.kind(),
            value.revision(), value.contentJson(), value.displayMarkdown(), value.createdAt())).toList();
    List<AgentWorkflowTransitionView> transitionViews = transitions
        .findByWorkflowIdOrderByCreatedAtAsc(workflow.id()).stream()
        .map(value -> new AgentWorkflowTransitionView(value.id(), value.fromStage(), value.toStage(),
            value.outcome(), value.actor(), value.reason(), value.createdAt())).toList();
    return new AgentWorkflowView(workflow.id(), workflow.chatId(), workflow.chatMessageIndex(),
        workflow.executionWorktreeId(), workflow.prompt(), workflow.status(), workflow.currentStage(),
        workflow.baselineHead(), workflow.currentPlanRevision(), workflow.approvedPlanRevision(),
        workflow.holdReason(), workflow.createdBy(), workflow.createdAt(), workflow.updatedAt(),
        workflow.completedAt(), artifactViews, transitionViews, stageRuns,
        stageRuns.stream().flatMap(run -> changes.listForAgentRun(run.id()).stream()).toList(),
        stageRuns.stream().flatMap(run -> tests.listForAgentRun(run.id()).stream()).toList());
  }

  private AgentWorkflow locked(UUID id) {
    return workflows.findForUpdateById(id).orElseThrow(() -> new IllegalArgumentException("Unknown workflow"));
  }
  private AgentWorkflow lockedOwned(UUID id, UUID actorId) {
    AgentWorkflow value = locked(id);
    if (!value.createdBy().equals(actorId)) throw new IllegalArgumentException("Unknown workflow");
    return value;
  }
  private int nextRevision(UUID workflowId, WorkflowArtifactKind kind) {
    return artifacts.findByWorkflowIdAndKindOrderByRevisionAsc(workflowId, kind).size() + 1;
  }
  private void transitionRecord(AgentWorkflow workflow, AgentWorkflowStage from, AgentWorkflowStage to,
      WorkflowGateOutcome outcome, String actor, String reason, Instant now) {
    transitions.save(new AgentWorkflowTransition(UUID.randomUUID(), workflow.id(), from, to,
        outcome, actor, reason, now));
  }
  private static String title(String prompt) {
    String value = prompt.strip().replaceAll("\\s+", " ");
    return value.length() <= 160 ? value : value.substring(0, 157) + "...";
  }
  private static String bounded(String value) {
    if (value == null) return null;
    String safe = value.replaceAll("[\\r\\n]+", " ").strip();
    return safe.substring(0, Math.min(2000, safe.length()));
  }
  private static String jsonEscape(String value) {
    if (value == null) return "Stage failed";
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\r", "\\r").replace("\n", "\\n");
  }
}
