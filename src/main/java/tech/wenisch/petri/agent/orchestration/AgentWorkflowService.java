package tech.wenisch.petri.agent.orchestration;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.git.ProjectWorktreeView;
import tech.wenisch.petri.git.WorkflowGitBaselineService;
import tech.wenisch.petri.git.WorkspaceGitService;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AgentWorkflowService {
  private static final int MAX_PROMPT = 200_000;
  private final AgentWorkflowLedger ledger;
  private final WorkflowCoordinator coordinator;
  private final WorkflowGitBaselineService baselines;
  private final ProjectWorktreeService worktrees;
  private final WorkspaceGitService git;
  private final ProjectRepository projects;
  private final AgentRunService runs;
  private final AgentChatStream stream;
  private final ObjectMapper mapper;

  AgentWorkflowService(
      AgentWorkflowLedger ledger, WorkflowCoordinator coordinator,
      WorkflowGitBaselineService baselines, ProjectWorktreeService worktrees,
      WorkspaceGitService git, ProjectRepository projects, AgentRunService runs,
      AgentChatStream stream, ObjectMapper mapper) {
    this.ledger = ledger; this.coordinator = coordinator; this.baselines = baselines;
    this.worktrees = worktrees; this.git = git; this.projects = projects; this.runs = runs;
    this.stream = stream; this.mapper = mapper;
  }

  public AgentWorkflowStartView create(
      UUID selectedWorktreeId, CreateAgentWorkflowCommand command,
      UUID creatorId, String actorEmail) {
    String prompt = required(command.prompt(), "Prompt", MAX_PROMPT);
    ProjectWorktreeView selected = worktrees.get(selectedWorktreeId);
    Project project = projects.findById(selected.projectId())
        .orElseThrow(() -> new IllegalArgumentException("Unknown project"));
    WorkflowBranchMode mode = command.branchMode() == null ? WorkflowBranchMode.CURRENT : command.branchMode();
    ProjectWorktreeView execution = selected;
    UUID parentChatId = null;
    UUID requestedChatId = command.chatId();
    if (mode == WorkflowBranchMode.NEW) {
      String branch = required(command.branchName(), "Branch name", 255);
      String start = selected.branchName() == null ? selected.gitRevision() : selected.branchName();
      execution = git.createBranch(project.getId(), branch, start, actorEmail);
      parentChatId = command.chatId();
      requestedChatId = null;
    } else {
      if (selected.branchName() != null && selected.branchName().equals(project.getDefaultBranch())
          && !command.confirmedDefaultBranch()) {
        throw new IllegalStateException(
            "Confirm explicitly before running an orchestrated workflow on the default branch");
      }
      if (requestedChatId != null) {
        AgentChatView chat = runs.chat(requestedChatId, creatorId);
        if (chat.executionMode() != AgentChatExecutionMode.ORCHESTRATED) {
          parentChatId = requestedChatId;
          requestedChatId = null;
        }
      }
    }
    UUID workflowId = UUID.randomUUID();
    AgentWorkflow workflow = ledger.create(workflowId, requestedChatId, parentChatId,
        execution.id(), project.getId(), prompt, creatorId, actorEmail, command.roleOverrides());
    if (parentChatId != null) storeForkContext(workflow.id(), parentChatId, creatorId);
    if (ledger.hasReservation(workflow.id())) {
      try {
        WorkflowGitBaselineService.Baseline baseline = baselines.create(execution.id(), workflow.id());
        workflow = ledger.baseline(workflow.id(), baseline.head(), baseline.tree(), actorEmail);
        coordinator.start(workflow.id());
      } catch (RuntimeException exception) {
        workflow = ledger.transition(workflow.id(), AgentWorkflowStage.HUMAN_HOLD,
            AgentWorkflowStatus.HUMAN_HOLD, WorkflowGateOutcome.HOLD, actorEmail,
            safe(exception.getMessage(), "Could not prepare workflow baseline"));
      }
    }
    AgentWorkflowView view = ledger.view(workflow.id(), creatorId);
    return new AgentWorkflowStartView(view, workflow.chatId(), execution.id(),
        "/workspace/projects/" + project.getId() + "?worktree=" + execution.id() + "&chat=" + workflow.chatId());
  }

  public AgentWorkflowView get(UUID workflowId, UUID actorId) {
    return ledger.view(workflowId, actorId);
  }

  public List<AgentWorkflowView> chatWorkflows(UUID chatId, UUID actorId) {
    runs.chat(chatId, actorId);
    return ledger.chatWorkflows(chatId).stream().map(ledger::view).toList();
  }

  public AgentWorkflowView answer(UUID workflowId, UUID actorId, JsonNode answers, String actor) {
    AgentWorkflow workflow = ledger.requireOwned(workflowId, actorId);
    if (workflow.currentStage() != AgentWorkflowStage.WAITING_FOR_CLARIFICATION) {
      throw new IllegalStateException("Workflow is not waiting for clarification");
    }
    if (answers == null || !answers.isObject() || answers.isEmpty()) {
      throw new IllegalArgumentException("Clarification answers are required");
    }
    ledger.storeUserArtifact(workflowId, WorkflowArtifactKind.ANSWERS, json(answers),
        "User answered the planner's clarification questions.");
    coordinator.clarificationAnswered(workflowId, actor);
    return ledger.view(workflowId, actorId);
  }

  public AgentWorkflowView approvePlan(UUID workflowId, int revision, UUID actorId, String actor) {
    ledger.approvePlan(workflowId, revision, actorId, actor);
    coordinator.planApproved(workflowId, actor);
    return ledger.view(workflowId, actorId);
  }

  public AgentWorkflowView approveFinal(UUID workflowId, UUID actorId, String actor) {
    ledger.requireOwned(workflowId, actorId);
    coordinator.finalApproved(workflowId, actor);
    return ledger.view(workflowId, actorId);
  }

  public AgentWorkflowView revisePlan(UUID workflowId, String feedback, UUID actorId, String actor) {
    AgentWorkflow workflow = ledger.requireOwned(workflowId, actorId);
    if (workflow.currentStage() != AgentWorkflowStage.WAITING_FOR_PLAN_APPROVAL) {
      throw new IllegalStateException("Workflow is not waiting for plan approval");
    }
    String safeFeedback = required(feedback, "Plan feedback", 20_000);
    ledger.storeUserArtifact(workflowId, WorkflowArtifactKind.USER_GUIDANCE,
        json(java.util.Map.of("feedback", safeFeedback)), safeFeedback);
    coordinator.planRevisionRequested(workflowId, actor);
    return ledger.view(workflowId, actorId);
  }

  public AgentWorkflowView guidance(UUID workflowId, String guidance, UUID actorId, String actor) {
    AgentWorkflow workflow = ledger.requireOwned(workflowId, actorId);
    if (workflow.terminal()) throw new IllegalStateException("Workflow is already terminal");
    String safeGuidance = required(guidance, "Guidance", 20_000);
    ledger.storeUserArtifact(workflowId, WorkflowArtifactKind.USER_GUIDANCE,
        json(java.util.Map.of("guidance", safeGuidance)), safeGuidance);
    boolean active = false;
    for (AgentRunView run : runs.workflowRuns(workflowId)) {
      if (run.status() == AgentRunStatus.RUNNING || run.status() == AgentRunStatus.PENDING) {
        if (run.status() == AgentRunStatus.RUNNING) active = true;
        runs.requestCancellation(run.id(), actorId, actor);
      }
    }
    coordinator.guidanceRequested(workflowId, active, actor);
    return ledger.view(workflowId, actorId);
  }

  public AgentWorkflowView retry(UUID workflowId, UUID actorId, String actor) {
    ledger.requireOwned(workflowId, actorId);
    coordinator.retry(workflowId, actor);
    return ledger.view(workflowId, actorId);
  }

  public AgentWorkflowView accept(UUID workflowId, UUID actorId, String actor) {
    ledger.requireOwned(workflowId, actorId);
    coordinator.acceptHeld(workflowId, actor);
    return ledger.view(workflowId, actorId);
  }

  public AgentWorkflowView cancel(UUID workflowId, UUID actorId, String actor) {
    AgentWorkflow workflow = ledger.requireOwned(workflowId, actorId);
    if (workflow.terminal()) return ledger.view(workflowId, actorId);
    for (AgentRunView run : runs.workflowRuns(workflowId)) {
      if (run.status() == AgentRunStatus.RUNNING || run.status() == AgentRunStatus.PENDING
          || run.status() == AgentRunStatus.CANCELLATION_REQUESTED) {
        runs.requestCancellation(run.id(), actorId, actor);
      }
    }
    AgentWorkflow cancelling = ledger.transition(workflowId, workflow.currentStage(),
        AgentWorkflowStatus.CANCELLING, WorkflowGateOutcome.USER_CANCELLED, actor,
        "Cancellation requested; applied changes will be preserved");
    stream.workflowStatus(cancelling.chatId(), ledger.view(cancelling));
    coordinator.cancelWhenSafe(workflowId, actor);
    return ledger.view(workflowId, actorId);
  }

  private String json(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (JacksonException exception) { throw new IllegalArgumentException("Request could not be encoded", exception); }
  }
  private void storeForkContext(UUID workflowId, UUID parentChatId, UUID creatorId) {
    AgentChatView parent = runs.chat(parentChatId, creatorId);
    List<AgentRunView> direct = runs.chatRuns(parentChatId);
    List<AgentWorkflow> gated = ledger.chatWorkflows(parentChatId);
    Object summary = runs.chatSummaries(parentChatId).isEmpty() ? null
        : runs.chatSummaries(parentChatId).getLast();
    List<?> recentDirect = direct.stream().skip(Math.max(0, direct.size() - 4L))
        .map(value -> java.util.Map.of("prompt", value.prompt(), "response",
            value.finalOutput() == null ? "" : value.finalOutput())).toList();
    List<?> recentGated = gated.stream().skip(Math.max(0, gated.size() - 3L))
        .map(value -> java.util.Map.of("prompt", value.prompt(), "status", value.status().name()))
        .toList();
    String context = json(java.util.Map.of(
        "parentChatId", parent.id(),
        "summary", summary == null ? "" : summary,
        "recentDirectMessages", recentDirect,
        "recentWorkflows", recentGated));
    ledger.storeUserArtifact(workflowId, WorkflowArtifactKind.CHAT_FORK_CONTEXT, context,
        "Context handed off from parent chat " + parent.id());
  }
  private static String required(String value, String label, int max) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
    if (value.length() > max) throw new IllegalArgumentException(label + " is too long");
    return value.strip();
  }
  private static String safe(String value, String fallback) {
    if (value == null || value.isBlank()) return fallback;
    String result = value.replaceAll("[\\r\\n]+", " ").strip();
    return result.substring(0, Math.min(2000, result.length()));
  }
}
