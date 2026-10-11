package tech.wenisch.petri.agent.orchestration;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.git.WorkflowGitBaselineService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Deterministic authority for every workflow transition. Model verdicts are evidence, not authority. */
@Service
public class WorkflowCoordinator {
  private final AgentWorkflowLedger ledger;
  private final AgentRunService runs;
  private final AgentRunLauncher launcher;
  private final WorkflowArtifactParser parser;
  private final WorkflowEvidenceService evidence;
  private final WorkflowGitBaselineService baselines;
  private final AgentChatStream stream;
  private final ObjectMapper mapper;
  private final Map<UUID, Object> workflowLocks = new ConcurrentHashMap<>();

  WorkflowCoordinator(
      AgentWorkflowLedger ledger, AgentRunService runs, AgentRunLauncher launcher,
      WorkflowArtifactParser parser, WorkflowEvidenceService evidence,
      WorkflowGitBaselineService baselines, AgentChatStream stream, ObjectMapper mapper) {
    this.ledger = ledger; this.runs = runs; this.launcher = launcher; this.parser = parser;
    this.evidence = evidence; this.baselines = baselines; this.stream = stream; this.mapper = mapper;
  }

  public void start(UUID workflowId) {
    launch(workflowId, AgentWorkflowStage.ORCHESTRATOR_INTAKE, AgentWorkflowRole.ORCHESTRATOR,
        intakePrompt(ledger.require(workflowId)), "workflow:start");
  }

  public void startNext(UUID worktreeId) {
    AgentWorkflow next = ledger.reserveNext(worktreeId);
    if (next == null) return;
    try {
      WorkflowGitBaselineService.Baseline baseline = baselines.create(worktreeId, next.id());
      ledger.baseline(next.id(), baseline.head(), baseline.tree(), "workflow:queue");
      start(next.id());
    } catch (RuntimeException exception) {
      hold(next, exception.getMessage() == null ? "Could not prepare workflow baseline"
          : exception.getMessage(), "workflow:queue");
    }
  }

  public void cancelWhenSafe(UUID workflowId, String actor) {
    synchronized (lock(workflowId)) {
      AgentWorkflow workflow = ledger.require(workflowId);
      if (workflow.terminal()) return;
      boolean active = runs.workflowRuns(workflowId).stream().anyMatch(run ->
          run.status() == AgentRunStatus.RUNNING
              || run.status() == AgentRunStatus.PENDING
              || run.status() == AgentRunStatus.CANCELLATION_REQUESTED);
      if (active) return;
      AgentWorkflow cancelled = ledger.transition(workflowId, AgentWorkflowStage.CANCELLED,
          AgentWorkflowStatus.CANCELLED, WorkflowGateOutcome.USER_CANCELLED, actor,
          "Workflow cancelled; applied changes were preserved");
      try { baselines.release(cancelled.executionWorktreeId(), cancelled.id()); }
      catch (RuntimeException ignored) { }
      stream.workflowStatus(cancelled.chatId(), ledger.view(cancelled));
      startNext(cancelled.executionWorktreeId());
    }
  }

  public void planApproved(UUID workflowId, String actor) {
    AgentWorkflow workflow = ledger.require(workflowId);
    if (workflow.approvedPlanRevision() == null) throw new IllegalStateException("Plan is not approved");
    launch(workflowId, AgentWorkflowStage.IMPLEMENTING, AgentWorkflowRole.IMPLEMENTER,
        implementationPrompt(workflow, null), actor);
  }

  public void clarificationAnswered(UUID workflowId, String actor) {
    AgentWorkflow workflow = ledger.require(workflowId);
    launch(workflowId, AgentWorkflowStage.PLANNING, AgentWorkflowRole.PLANNER,
        planningPrompt(workflow), actor);
  }

  public void planRevisionRequested(UUID workflowId, String actor) {
    AgentWorkflow workflow = ledger.invalidateApproval(workflowId);
    launch(workflowId, AgentWorkflowStage.PLANNING, AgentWorkflowRole.PLANNER,
        planningPrompt(workflow), actor);
  }

  public void guidanceRequested(UUID workflowId, boolean waitingForActiveRun, String actor) {
    AgentWorkflow workflow = ledger.invalidateApproval(workflowId);
    if (waitingForActiveRun) {
      AgentWorkflow queued = ledger.transition(workflowId, AgentWorkflowStage.PLANNING,
          AgentWorkflowStatus.PENDING, WorkflowGateOutcome.USER_REVISED, actor,
          "User guidance queued for the next safe execution boundary");
      stream.workflowStatus(queued.chatId(), ledger.view(queued));
    } else {
      launch(workflowId, AgentWorkflowStage.PLANNING, AgentWorkflowRole.PLANNER,
          planningPrompt(workflow), actor);
    }
  }

  public void retry(UUID workflowId, String actor) {
    AgentWorkflow workflow = ledger.require(workflowId);
    if (workflow.currentStage() != AgentWorkflowStage.HUMAN_HOLD) {
      throw new IllegalStateException("Only a held workflow can be retried");
    }
    AgentWorkflowStage phase = resumePhase(workflow);
    AgentWorkflowRole role = role(phase);
    launch(workflowId, phase, role, promptFor(workflow, phase), actor);
  }

  @EventListener
  void stageFinished(AgentRunTerminalEvent event) {
    AgentRunView run = runs.get(event.runId());
    if (run.workflowId() == null) return;
    synchronized (lock(run.workflowId())) {
      try { processTerminal(run); }
      catch (RuntimeException exception) { failClosed(run.workflowId(), exception); }
    }
  }

  /** Repairs the short gaps between a committed stage result and the next stage launch. */
  @Scheduled(initialDelayString = "${petri.agent.workflow-recovery-initial-delay-ms:15000}",
      fixedDelayString = "${petri.agent.workflow-recovery-delay-ms:30000}")
  public void recover() {
    for (AgentWorkflow workflow : ledger.recoverable()) {
      synchronized (lock(workflow.id())) {
        try { recover(workflow.id()); }
        catch (RuntimeException exception) { failClosed(workflow.id(), exception); }
      }
    }
  }

  private void recover(UUID workflowId) {
    AgentWorkflow workflow = ledger.require(workflowId);
    if (workflow.status() == AgentWorkflowStatus.CANCELLING) {
      cancelWhenSafe(workflow.id(), "workflow:recovery");
      return;
    }
    if (workflow.terminal() || workflow.status() == AgentWorkflowStatus.WAITING_FOR_USER
        || workflow.status() == AgentWorkflowStatus.HUMAN_HOLD) return;
    if (workflow.currentStage() == AgentWorkflowStage.QUEUED) {
      if (ledger.hasReservation(workflow.id())) startNextReserved(workflow);
      else startNext(workflow.executionWorktreeId());
      return;
    }
    List<AgentRunView> phaseRuns = runs.workflowRuns(workflow.id()).stream()
        .filter(run -> run.workflowPhase() == workflow.currentStage()).toList();
    AgentRunView latest = phaseRuns.isEmpty() ? null : phaseRuns.getLast();
    if (latest == null) {
      launch(workflow.id(), workflow.currentStage(), role(workflow.currentStage()),
          promptFor(workflow, workflow.currentStage()), "workflow:recovery");
      return;
    }
    if (latest.status() == AgentRunStatus.PENDING) {
      launcher.launch(latest.id(), latest.worktreeId(), latest.createdBy(),
          "workflow-recovery:" + workflow.id());
    } else if (terminal(latest.status())) {
      AgentWorkflowArtifact artifact = ledger.artifactForRun(latest.id());
      if (artifact != null && artifact.kind() == WorkflowArtifactKind.STAGE_FAILURE) {
        if (workflow.status() == AgentWorkflowStatus.PENDING) {
          launch(workflow.id(), workflow.currentStage(), role(workflow.currentStage()),
              promptFor(workflow, workflow.currentStage()), "workflow:recovery");
        }
      } else {
        processTerminal(latest);
      }
    }
  }

  private void processTerminal(AgentRunView run) {
    AgentWorkflow workflow = ledger.require(run.workflowId());
    if (workflow.terminal()) return;
    if (workflow.status() == AgentWorkflowStatus.CANCELLING) {
      cancelWhenSafe(workflow.id(), "workflow:cancellation");
      return;
    }
    if (run.workflowPhase() != workflow.currentStage()) {
      if (workflow.status() == AgentWorkflowStatus.PENDING) {
        launch(workflow.id(), workflow.currentStage(), role(workflow.currentStage()),
            promptFor(workflow, workflow.currentStage()), "workflow:safe-boundary");
      }
      return;
    }
    if (run.status() != AgentRunStatus.COMPLETED) {
      if (ledger.artifactForRun(run.id()) != null) return;
      String reason = run.errorMessage() == null || run.errorMessage().isBlank()
          ? "Stage run ended with " + run.status()
          : "Stage model request failed: " + run.errorMessage();
      workflow = ledger.recordStageFailure(workflow.id(), run.id(),
          reason, "workflow:" + workflow.id());
      retryOrHold(workflow, run.workflowPhase(), run.workflowRole(),
          reason, "workflow:" + workflow.id());
      return;
    }
    WorkflowArtifactParser.Parsed parsed;
    try {
      parsed = parser.parse(run.workflowPhase(), run.finalOutput());
    } catch (RuntimeException exception) {
      if (ledger.artifactForRun(run.id()) != null) return;
      workflow = ledger.recordStageFailure(workflow.id(), run.id(), exception.getMessage(),
          "workflow:" + workflow.id());
      retryOrHold(workflow, run.workflowPhase(), run.workflowRole(), exception.getMessage(),
          "workflow:" + workflow.id(), true);
      return;
    }
    AgentWorkflowArtifact artifact = ledger.store(workflow.id(), run.id(), parsed);
    stream.workflowArtifact(workflow.chatId(), workflow.id(), artifactView(artifact));
    advance(workflow, run.workflowPhase(), parsed);
  }

  private void advance(
      AgentWorkflow workflow, AgentWorkflowStage phase, WorkflowArtifactParser.Parsed parsed) {
    switch (phase) {
      case ORCHESTRATOR_INTAKE -> launch(workflow.id(), AgentWorkflowStage.PLANNING,
          AgentWorkflowRole.PLANNER, planningPrompt(workflow), "orchestrator:intake");
      case PLANNING -> {
        if (parsed.kind() == WorkflowArtifactKind.QUESTIONS) {
          waitForUser(workflow, AgentWorkflowStage.WAITING_FOR_CLARIFICATION,
              "Planner needs clarification");
        } else {
          launch(workflow.id(), AgentWorkflowStage.ORCHESTRATOR_PLAN_REVIEW,
              AgentWorkflowRole.ORCHESTRATOR, planReviewPrompt(workflow), "planner");
        }
      }
      case ORCHESTRATOR_PLAN_REVIEW -> planVerdict(workflow, parsed.value());
      case IMPLEMENTING -> implementationFinished(workflow);
      case ORCHESTRATOR_IMPLEMENTATION_REVIEW -> verdict(workflow, parsed.value(),
          AgentWorkflowStage.REVIEWING, AgentWorkflowStage.IMPLEMENTING,
          AgentWorkflowRole.IMPLEMENTER, "Implementation evidence accepted");
      case REVIEWING -> launch(workflow.id(), AgentWorkflowStage.ORCHESTRATOR_FINAL_REVIEW,
          AgentWorkflowRole.ORCHESTRATOR, finalReviewPrompt(workflow), "reviewer");
      case ORCHESTRATOR_FINAL_REVIEW -> finalVerdict(workflow, parsed.value());
      default -> hold(workflow, "Unexpected stage completion", "workflow:" + workflow.id());
    }
  }

  private void implementationFinished(AgentWorkflow workflow) {
    WorkflowEvidenceService.Evidence value = evidence.collect(workflow);
    storeDiff(workflow, value);
    if (value.openActions()) {
      hold(workflow, "Implementation left approval actions open", "workflow:" + workflow.id());
    } else if (value.conflict() || value.testsFailed()) {
      retryOrHold(workflow, AgentWorkflowStage.IMPLEMENTING, AgentWorkflowRole.IMPLEMENTER,
          value.conflict() ? "A proposed file change conflicted" : "An approved test failed",
          "workflow:" + workflow.id());
    } else {
      launch(workflow.id(), AgentWorkflowStage.ORCHESTRATOR_IMPLEMENTATION_REVIEW,
          AgentWorkflowRole.ORCHESTRATOR, implementationReviewPrompt(workflow, value), "implementer");
    }
  }

  private void verdict(
      AgentWorkflow workflow, JsonNode verdict, AgentWorkflowStage pass,
      AgentWorkflowStage revision, AgentWorkflowRole revisionRole, String passReason) {
    String decision = verdict.path("decision").asText();
    String reason = verdict.path("reason").asText();
    if ("PASS".equals(decision)) {
      if (pass == AgentWorkflowStage.WAITING_FOR_PLAN_APPROVAL) {
        waitForUser(workflow, pass, passReason);
      } else {
        launch(workflow.id(), pass, role(pass), promptFor(workflow, pass), "orchestrator");
      }
    } else if ("REVISION_REQUIRED".equals(decision)) {
      launch(workflow.id(), revision, revisionRole, promptFor(workflow, revision), "orchestrator:" + reason);
    } else {
      hold(workflow, reason, "orchestrator");
    }
  }

  private void planVerdict(AgentWorkflow workflow, JsonNode verdict) {
    String decision = verdict.path("decision").asText();
    String reason = verdict.path("reason").asText();
    if ("PASS".equals(decision) || "PASS_WITH_WARNINGS".equals(decision)) {
      waitForUser(workflow, AgentWorkflowStage.WAITING_FOR_PLAN_APPROVAL,
          "PASS_WITH_WARNINGS".equals(decision)
              ? "Plan is ready for user review with warnings: " + reason
              : "Plan passed orchestration review");
    } else if ("REVISION_REQUIRED".equals(decision)) {
      launch(workflow.id(), AgentWorkflowStage.PLANNING, AgentWorkflowRole.PLANNER,
          promptFor(workflow, AgentWorkflowStage.PLANNING), "orchestrator:" + reason);
    } else if (verdict.path("hardBlock").asBoolean(false)) {
      hold(workflow, reason, "orchestrator");
    } else {
      waitForUser(workflow, AgentWorkflowStage.WAITING_FOR_PLAN_APPROVAL,
          "Plan is ready for user review with warnings: " + reason);
    }
  }

  private void finalVerdict(AgentWorkflow workflow, JsonNode verdict) {
    JsonNode review = read(ledger.latest(workflow.id(), WorkflowArtifactKind.REVIEW).contentJson());
    String decision = verdict.path("decision").asText();
    if ("PASS".equals(decision) && "APPROVED".equals(review.path("decision").asText())) {
      AgentWorkflow completed = ledger.transition(workflow.id(), AgentWorkflowStage.COMPLETED,
          AgentWorkflowStatus.COMPLETED, WorkflowGateOutcome.PASS, "orchestrator",
          verdict.path("reason").asText("Workflow reviewed and completed"));
      try { baselines.release(completed.executionWorktreeId(), completed.id()); } catch (RuntimeException ignored) { }
      stream.workflowStatus(completed.chatId(), ledger.view(completed));
      startNext(completed.executionWorktreeId());
    } else if ("REVISION_REQUIRED".equals(decision)
        || "CHANGES_REQUIRED".equals(review.path("decision").asText())) {
      launch(workflow.id(), AgentWorkflowStage.IMPLEMENTING, AgentWorkflowRole.IMPLEMENTER,
          implementationPrompt(workflow, review.toString()), "orchestrator:review-revision");
    } else {
      hold(workflow, verdict.path("reason").asText("Final review could not pass"), "orchestrator");
    }
  }

  private void launch(UUID workflowId, AgentWorkflowStage phase, AgentWorkflowRole role,
      String prompt, String actor) {
    AgentWorkflow workflow = ledger.require(workflowId);
    AgentWorkflowRoleSnapshot snapshot = ledger.snapshot(workflowId, role);
    int attempt = Math.toIntExact(ledger.attempts(workflowId, phase) + 1);
    if (attempt > snapshot.maxAttempts()) {
      hold(workflow, "Maximum attempts reached for " + phase, actor);
      return;
    }
    AgentWorkflow moved = ledger.transition(workflowId, phase, AgentWorkflowStatus.RUNNING,
        attempt == 1 ? WorkflowGateOutcome.STARTED : WorkflowGateOutcome.RETRIED,
        actor, "Started " + phase + " attempt " + attempt);
    AgentRunView run = runs.createWorkflowStage(moved, snapshot, phase, attempt, prompt, actor);
    stream.workflowStatus(moved.chatId(), ledger.view(moved));
    launcher.launch(run.id(), run.worktreeId(), run.createdBy(), "workflow:" + workflowId);
  }

  private void retryOrHold(AgentWorkflow workflow, AgentWorkflowStage phase, AgentWorkflowRole role,
      String reason, String actor) {
    retryOrHold(workflow, phase, role, reason, actor, false);
  }

  private void retryOrHold(AgentWorkflow workflow, AgentWorkflowStage phase, AgentWorkflowRole role,
      String reason, String actor, boolean structuredResponseFailure) {
    AgentWorkflowRoleSnapshot snapshot = ledger.snapshot(workflow.id(), role);
    if (ledger.attempts(workflow.id(), phase) >= snapshot.maxAttempts()) {
      String finalReason = structuredResponseFailure
          ? "The configured model could not produce the required structured response after "
              + snapshot.maxAttempts() + " attempts. Try another model or retry the workflow."
          : reason;
      hold(workflow, finalReason, actor);
    } else {
      String prompt = promptFor(workflow, phase);
      if (structuredResponseFailure) {
        prompt += "\n\nFORMAT REPAIR REQUIRED: The previous response was rejected because: "
            + reason + ". Return exactly one complete JSON object matching the contract. Do not use "
            + "Markdown fences and do not include any explanation before or after the object.";
      }
      launch(workflow.id(), phase, role, prompt, actor);
    }
  }

  private void hold(AgentWorkflow workflow, String reason, String actor) {
    AgentWorkflow held = ledger.transition(workflow.id(), AgentWorkflowStage.HUMAN_HOLD,
        AgentWorkflowStatus.HUMAN_HOLD, WorkflowGateOutcome.HOLD, actor, reason);
    stream.workflowStatus(held.chatId(), ledger.view(held));
  }

  private void waitForUser(AgentWorkflow workflow, AgentWorkflowStage stage, String reason) {
    AgentWorkflow waiting = ledger.transition(workflow.id(), stage, AgentWorkflowStatus.WAITING_FOR_USER,
        WorkflowGateOutcome.PASS, "workflow", reason);
    stream.workflowStatus(waiting.chatId(), ledger.view(waiting));
  }

  private void storeDiff(AgentWorkflow workflow, WorkflowEvidenceService.Evidence value) {
    String json = json(Map.of("paths", value.touchedPaths(), "diff", value.diff(),
        "finalHashes", value.finalHashes(),
        "tests", value.tests(), "proposals", value.proposals()));
    ledger.storeUserArtifact(workflow.id(), WorkflowArtifactKind.TASK_DIFF, json,
        value.diff().isBlank() ? "No workflow-owned file changes." : "```diff\n" + value.diff() + "\n```");
  }

  private String intakePrompt(AgentWorkflow workflow) {
    String context = latestOptional(workflow.id(), WorkflowArtifactKind.CHAT_FORK_CONTEXT);
    return "Create the planner handoff for this user request:\n\n" + workflow.prompt()
        + (context == null ? "" : "\n\nBOUNDED PARENT CHAT CONTEXT:\n" + context) + "\n\n"
        + "Return JSON: {\"objective\":\"...\",\"constraints\":[\"...\"],"
        + "\"successCriteria\":[\"...\"]}.";
  }

  private String planningPrompt(AgentWorkflow workflow) {
    StringBuilder prompt = new StringBuilder("Plan the task using this orchestrator brief:\n")
        .append(ledger.latest(workflow.id(), WorkflowArtifactKind.PLANNER_BRIEF).contentJson());
    appendOptional(prompt, workflow.id(), WorkflowArtifactKind.ANSWERS, "User answers");
    appendOptional(prompt, workflow.id(), WorkflowArtifactKind.USER_GUIDANCE, "User guidance");
    appendOptional(prompt, workflow.id(), WorkflowArtifactKind.PLAN_VERDICT,
        "Orchestrator feedback on the previous plan");
    prompt.append("\nReturn either {\"kind\":\"QUESTIONS\",\"questions\":[{\"id\":\"q1\","
        + "\"type\":\"SINGLE_SELECT|MULTI_SELECT|FREE_TEXT\",\"prompt\":\"...\","
        + "\"options\":[\"...\"]}]} or {\"kind\":\"PLAN\",\"title\":\"...\","
        + "\"objective\":\"...\",\"assumptions\":[],\"steps\":[\"...\"],"
        + "\"files\":[],\"tests\":[],\"risks\":[],\"acceptanceCriteria\":[\"...\"]}.");
    return prompt.toString();
  }

  private String planReviewPrompt(AgentWorkflow workflow) {
    return "Validate this plan against the original request. Use read-only tools to verify claims "
        + "about the current repository before judging them. Reasonable technology defaults or "
        + "non-destructive uncertainty must be PASS_WITH_WARNINGS so the user can decide. "
        + "Use REVISION_REQUIRED only for an actionable defect the planner should fix. Use HOLD with "
        + "hardBlock=true only for irreversible/destructive, credential-exposing, permission-bypassing, "
        + "or otherwise unauthorized work.\nREQUEST:\n" + workflow.prompt()
        + "\nPLAN:\n" + ledger.latest(workflow.id(), WorkflowArtifactKind.PLAN).contentJson()
        + planVerdictContract();
  }

  private String implementationPrompt(AgentWorkflow workflow, String feedback) {
    String value = "Implement this exact approved plan:\n"
        + ledger.latest(workflow.id(), WorkflowArtifactKind.PLAN).contentJson();
    String implementationVerdict = latestOptional(workflow.id(), WorkflowArtifactKind.IMPLEMENTATION_VERDICT);
    String priorReport = latestOptional(workflow.id(), WorkflowArtifactKind.IMPLEMENTATION_REPORT);
    String priorEvidence = latestOptional(workflow.id(), WorkflowArtifactKind.TASK_DIFF);
    String guidance = latestOptional(workflow.id(), WorkflowArtifactKind.USER_GUIDANCE);
    if (implementationVerdict != null) value += "\nIMPLEMENTATION GATE FEEDBACK:\n" + implementationVerdict;
    if (priorReport != null) value += "\nPREVIOUS IMPLEMENTATION REPORT:\n" + priorReport;
    if (priorEvidence != null) value += "\nCURRENT TASK EVIDENCE:\n" + priorEvidence;
    if (guidance != null) value += "\nUSER GUIDANCE:\n" + guidance;
    if (feedback != null) value += "\nREVIEW FEEDBACK:\n" + feedback;
    return value + "\nReturn JSON: {\"summary\":\"...\",\"changedPaths\":[\"...\"],"
        + "\"tests\":[\"...\"],\"deviations\":[\"...\"]}.";
  }

  private String implementationReviewPrompt(AgentWorkflow workflow, WorkflowEvidenceService.Evidence evidence) {
    return "Check implementation readiness and plan coverage.\nPLAN:\n"
        + ledger.latest(workflow.id(), WorkflowArtifactKind.PLAN).contentJson()
        + "\nIMPLEMENTATION REPORT:\n"
        + ledger.latest(workflow.id(), WorkflowArtifactKind.IMPLEMENTATION_REPORT).contentJson()
        + "\nCANONICAL TASK DIFF:\n" + evidence.diff() + "\nTESTS:\n" + json(evidence.tests())
        + verdictContract();
  }

  private String reviewPrompt(AgentWorkflow workflow) {
    return "Review the implementation against the approved plan.\nREQUEST:\n" + workflow.prompt()
        + "\nPLAN:\n" + ledger.latest(workflow.id(), WorkflowArtifactKind.PLAN).contentJson()
        + "\nTASK DIFF:\n" + ledger.latest(workflow.id(), WorkflowArtifactKind.TASK_DIFF).contentJson()
        + "\nReturn JSON: {\"decision\":\"APPROVED|CHANGES_REQUIRED|HOLD\","
        + "\"summary\":\"...\",\"findings\":[{\"severity\":\"...\",\"path\":\"...\","
        + "\"line\":1,\"issue\":\"...\",\"requiredChange\":\"...\"}],\"planCoverage\":[]}.";
  }

  private String finalReviewPrompt(AgentWorkflow workflow) {
    return "Issue the final workflow verdict.\nPLAN:\n"
        + ledger.latest(workflow.id(), WorkflowArtifactKind.PLAN).contentJson()
        + "\nTASK DIFF:\n" + ledger.latest(workflow.id(), WorkflowArtifactKind.TASK_DIFF).contentJson()
        + "\nREVIEW:\n" + ledger.latest(workflow.id(), WorkflowArtifactKind.REVIEW).contentJson()
        + verdictContract();
  }

  private String promptFor(AgentWorkflow workflow, AgentWorkflowStage phase) {
    return switch (phase) {
      case ORCHESTRATOR_INTAKE -> intakePrompt(workflow);
      case PLANNING -> planningPrompt(workflow);
      case ORCHESTRATOR_PLAN_REVIEW -> planReviewPrompt(workflow);
      case IMPLEMENTING -> implementationPrompt(workflow, latestOptional(workflow.id(), WorkflowArtifactKind.REVIEW));
      case ORCHESTRATOR_IMPLEMENTATION_REVIEW -> implementationReviewPrompt(workflow, evidence.collect(workflow));
      case REVIEWING -> reviewPrompt(workflow);
      case ORCHESTRATOR_FINAL_REVIEW -> finalReviewPrompt(workflow);
      default -> throw new IllegalStateException("Stage cannot be launched: " + phase);
    };
  }

  private AgentWorkflowStage resumePhase(AgentWorkflow workflow) {
    if (latestOptional(workflow.id(), WorkflowArtifactKind.PLANNER_BRIEF) == null) return AgentWorkflowStage.ORCHESTRATOR_INTAKE;
    if (latestOptional(workflow.id(), WorkflowArtifactKind.PLAN) == null) return AgentWorkflowStage.PLANNING;
    if (workflow.approvedPlanRevision() == null) return AgentWorkflowStage.ORCHESTRATOR_PLAN_REVIEW;
    if (latestOptional(workflow.id(), WorkflowArtifactKind.IMPLEMENTATION_REPORT) == null) return AgentWorkflowStage.IMPLEMENTING;
    if (latestOptional(workflow.id(), WorkflowArtifactKind.REVIEW) == null) return AgentWorkflowStage.ORCHESTRATOR_IMPLEMENTATION_REVIEW;
    return AgentWorkflowStage.ORCHESTRATOR_FINAL_REVIEW;
  }

  private static AgentWorkflowRole role(AgentWorkflowStage phase) {
    return switch (phase) {
      case ORCHESTRATOR_INTAKE, ORCHESTRATOR_PLAN_REVIEW,
          ORCHESTRATOR_IMPLEMENTATION_REVIEW, ORCHESTRATOR_FINAL_REVIEW -> AgentWorkflowRole.ORCHESTRATOR;
      case PLANNING -> AgentWorkflowRole.PLANNER;
      case IMPLEMENTING -> AgentWorkflowRole.IMPLEMENTER;
      case REVIEWING -> AgentWorkflowRole.REVIEWER;
      default -> throw new IllegalArgumentException("Stage has no model role: " + phase);
    };
  }

  private static String verdictContract() {
    return "\nReturn JSON: {\"decision\":\"PASS|REVISION_REQUIRED|HOLD\","
        + "\"reason\":\"...\",\"feedback\":[\"...\"]}.";
  }
  private static String planVerdictContract() {
    return "\nReturn JSON: {\"decision\":\"PASS|PASS_WITH_WARNINGS|REVISION_REQUIRED|HOLD\","
        + "\"reason\":\"...\",\"feedback\":[\"...\"],\"warnings\":[\"...\"],"
        + "\"hardBlock\":false}. HOLD is valid only with hardBlock=true; PASS_WITH_WARNINGS "
        + "requires at least one warning.";
  }
  private String latestOptional(UUID workflowId, WorkflowArtifactKind kind) {
    return ledger.artifacts(workflowId).stream().filter(value -> value.kind() == kind)
        .reduce((first, second) -> second).map(AgentWorkflowArtifact::contentJson).orElse(null);
  }
  private void appendOptional(StringBuilder value, UUID workflowId, WorkflowArtifactKind kind, String label) {
    String content = latestOptional(workflowId, kind);
    if (content != null) value.append("\n").append(label).append(":\n").append(content);
  }
  private JsonNode read(String json) {
    try { return mapper.readTree(json); }
    catch (JacksonException exception) { throw new IllegalStateException("Stored workflow artifact is invalid", exception); }
  }
  private String json(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (JacksonException exception) { throw new IllegalStateException("Workflow evidence could not be encoded", exception); }
  }
  private static AgentWorkflowArtifactView artifactView(AgentWorkflowArtifact value) {
    return new AgentWorkflowArtifactView(value.id(), value.agentRunId(), value.kind(), value.revision(),
        value.contentJson(), value.displayMarkdown(), value.createdAt());
  }
  private void failClosed(UUID workflowId, RuntimeException exception) {
    AgentWorkflow workflow = ledger.require(workflowId);
    if (workflow.terminal() || workflow.status() == AgentWorkflowStatus.HUMAN_HOLD
        || workflow.status() == AgentWorkflowStatus.CANCELLING) return;
    String reason = exception.getMessage();
    hold(workflow, reason == null || reason.isBlank()
        ? "Workflow coordination failed safely" : reason, "workflow:coordinator");
  }
  private void startNextReserved(AgentWorkflow workflow) {
    try {
      WorkflowGitBaselineService.Baseline baseline = baselines.create(
          workflow.executionWorktreeId(), workflow.id());
      ledger.baseline(workflow.id(), baseline.head(), baseline.tree(), "workflow:recovery");
      start(workflow.id());
    } catch (RuntimeException exception) {
      hold(workflow, exception.getMessage() == null ? "Could not prepare workflow baseline"
          : exception.getMessage(), "workflow:recovery");
    }
  }

  private Object lock(UUID workflowId) {
    return workflowLocks.computeIfAbsent(workflowId, ignored -> new Object());
  }
  private static boolean terminal(AgentRunStatus status) {
    return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED
        || status == AgentRunStatus.BUDGET_EXHAUSTED || status == AgentRunStatus.CANCELLED;
  }
}
