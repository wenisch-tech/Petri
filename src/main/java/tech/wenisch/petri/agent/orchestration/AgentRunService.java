package tech.wenisch.petri.agent.orchestration;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.agent.model.ModelCredentialStatus;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.security.AppUserRepository;
import tech.wenisch.petri.workspace.WorktreeLocator;
import tech.wenisch.petri.git.WorkspaceGitOperationCoordinator;

@Service
public class AgentRunService {
  /** An execution that has not renewed its lease for this long has no live executor. */
  private static final java.time.Duration ABANDONED_AFTER = java.time.Duration.ofSeconds(90);

  private static final int MAX_PROMPT_LENGTH = 200_000;
  private static final int DEFAULT_MAX_MODEL_TURNS = 20;

  private final AgentRunRepository runs;
  private final AgentChatRepository chats;
  private final AgentChatSummaryRepository summaries;
  private final AgentTurnRepository turns;
  private final ModelCredentialService credentials;
  private final AppUserRepository users;
  private final WorktreeLocator worktrees;
  private final AuditLogService auditLog;
  private final WorkspaceGitOperationCoordinator gitOperations;
  private final WorkflowReservationService workflowReservations;
  private final Clock clock;

  @Autowired
  public AgentRunService(
      AgentRunRepository runs,
      AgentChatRepository chats,
      AgentChatSummaryRepository summaries,
      AgentTurnRepository turns,
      ModelCredentialService credentials,
      AppUserRepository users,
      WorktreeLocator worktrees,
      AuditLogService auditLog,
      WorkspaceGitOperationCoordinator gitOperations,
      WorkflowReservationService workflowReservations) {
    this(runs, chats, summaries, turns, credentials, users, worktrees, auditLog,
        gitOperations, workflowReservations, Clock.systemUTC());
  }

  AgentRunService(
      AgentRunRepository runs,
      AgentChatRepository chats,
      AgentChatSummaryRepository summaries,
      AgentTurnRepository turns,
      ModelCredentialService credentials,
      AppUserRepository users,
      WorktreeLocator worktrees,
      AuditLogService auditLog,
      WorkspaceGitOperationCoordinator gitOperations,
      WorkflowReservationService workflowReservations,
      Clock clock) {
    this.runs = runs;
    this.chats = chats;
    this.summaries = summaries;
    this.turns = turns;
    this.credentials = credentials;
    this.users = users;
    this.worktrees = worktrees;
    this.auditLog = auditLog;
    this.gitOperations = gitOperations;
    this.workflowReservations = workflowReservations;
    this.clock = clock;
  }

  @Transactional
  public AgentRunView create(
      UUID worktreeId,
      CreateAgentRunCommand command,
      UUID actorId,
      String auditActor) {
    worktrees.requireActiveWorktree(worktreeId);
    workflowReservations.assertUnreserved(worktreeId);
    gitOperations.assertAgentAllowed(worktreeId);
    if (!users.existsById(actorId)) throw new IllegalArgumentException("Unknown run creator");
    String prompt = required(command.prompt(), "Prompt", MAX_PROMPT_LENGTH);
    ModelCredentialStatus credential = credentials.resolve(
        actorId, command.provider(), command.providerInstanceId());
    ModelProvider provider = credential.provider();
    String model = command.model() == null || command.model().isBlank()
        ? credential.model()
        : required(command.model(), "Model", 200).strip();
    int maxOutputTokens = command.maxOutputTokens() == null
        ? credential.maxOutputTokens()
        : command.maxOutputTokens();
    if (maxOutputTokens < 16 || maxOutputTokens > 1_000_000) {
      throw new IllegalArgumentException("Maximum output tokens must be between 16 and 1000000");
    }
    int maxTurns = command.maxModelTurns() == null
        ? DEFAULT_MAX_MODEL_TURNS
        : command.maxModelTurns();
    if (maxTurns < 1 || maxTurns > 100) {
      throw new IllegalArgumentException("Maximum model turns must be between 1 and 100");
    }
    int contextWindowTokens = command.contextWindowTokens() == null
        ? credential.contextWindowTokens()
        : command.contextWindowTokens();
    validateContextWindow(contextWindowTokens, maxOutputTokens);
    Instant now = Instant.now(clock);
    AgentChat chat = chats.save(new AgentChat(
        UUID.randomUUID(), worktreeId, title(prompt), actorId, now));
    AgentRun run = createRun(chat, prompt, provider, credential.id(), model, maxTurns, maxOutputTokens,
        contextWindowTokens,
        command.reasoningEffort() == null ? credential.reasoningEffort() : command.reasoningEffort(),
        command.textVerbosity() == null ? credential.textVerbosity() : command.textVerbosity(),
        actorId, now);
    auditLog.record(
        auditActor, "AGENT_RUN_CREATED", "AGENT_RUN", run.id(),
        "Created agent run for worktree " + worktreeId);
    return view(run, turns.findByRunIdOrderByIndexAsc(run.id()));
  }

  /**
   * Creates a run that belongs to an unattended automation: it keeps its own instructions and is
   * limited to the automation tool set. The run is not started here.
   */
  @Transactional
  public AgentRunView createAutomationRun(
      UUID worktreeId,
      CreateAgentRunCommand command,
      UUID actorId,
      String auditActor,
      UUID automationRunId,
      String instructions) {
    AgentRunView created = create(worktreeId, command, actorId, auditActor);
    AgentRun run = runs.findById(created.id())
        .orElseThrow(() -> new IllegalStateException("Created agent run disappeared"));
    run.useAutomation(automationRunId, instructions);
    return created;
  }

  @Transactional
  public AgentRunView enqueue(
      UUID chatId, CreateAgentRunCommand command, UUID actorId, String auditActor) {
    AgentChat chat = lockedChat(chatId);
    if (!chat.createdBy().equals(actorId)) throw new IllegalArgumentException("Chat belongs to another creator");
    if (chat.executionMode() != AgentChatExecutionMode.DIRECT) {
      throw new IllegalStateException("Orchestrated chats accept workflow tasks, not direct runs");
    }
    worktrees.requireActiveWorktree(chat.worktreeId());
    workflowReservations.assertUnreserved(chat.worktreeId());
    gitOperations.assertAgentAllowed(chat.worktreeId());
    if (!users.existsById(actorId)) throw new IllegalArgumentException("Unknown run creator");
    String prompt = required(command.prompt(), "Prompt", MAX_PROMPT_LENGTH);
    ModelCredentialStatus credential = credentials.resolve(
        actorId, command.provider(), command.providerInstanceId());
    ModelProvider provider = credential.provider();
    String model = command.model() == null || command.model().isBlank()
        ? credential.model() : required(command.model(), "Model", 200).strip();
    int maxOutputTokens = command.maxOutputTokens() == null
        ? credential.maxOutputTokens() : command.maxOutputTokens();
    if (maxOutputTokens < 16 || maxOutputTokens > 1_000_000) {
      throw new IllegalArgumentException("Maximum output tokens must be between 16 and 1000000");
    }
    int contextWindowTokens = command.contextWindowTokens() == null
        ? credential.contextWindowTokens() : command.contextWindowTokens();
    validateContextWindow(contextWindowTokens, maxOutputTokens);
    int maxTurns = command.maxModelTurns() == null ? DEFAULT_MAX_MODEL_TURNS : command.maxModelTurns();
    if (maxTurns < 1 || maxTurns > 100) {
      throw new IllegalArgumentException("Maximum model turns must be between 1 and 100");
    }
    Instant now = Instant.now(clock);
    AgentRun run = createRun(chat, prompt, provider, credential.id(), model, maxTurns, maxOutputTokens,
        contextWindowTokens,
        command.reasoningEffort() == null ? credential.reasoningEffort() : command.reasoningEffort(),
        command.textVerbosity() == null ? credential.textVerbosity() : command.textVerbosity(),
        actorId, now);
    auditLog.record(
        auditActor, "AGENT_RUN_CREATED", "AGENT_RUN", run.id(),
        "Created agent run for worktree " + chat.worktreeId());
    return view(run, turns.findByRunIdOrderByIndexAsc(run.id()));
  }

  @Transactional
  AgentRunView createWorkflowStage(
      AgentWorkflow workflow,
      AgentWorkflowRoleSnapshot snapshot,
      AgentWorkflowStage phase,
      int attempt,
      String prompt,
      String auditActor) {
    if (workflow.terminal()) throw new IllegalStateException("Workflow is already terminal");
    String safePrompt = required(prompt, "Stage prompt", MAX_PROMPT_LENGTH);
    Instant now = Instant.now(clock);
    AgentRun stage = new AgentRun(
        UUID.randomUUID(), workflow.chatId(), workflow.id(), snapshot.role(), phase, attempt,
        workflow.executionWorktreeId(), snapshot.provider(), snapshot.model(), safePrompt,
        snapshot.maxModelTurns(), snapshot.maxOutputTokens(), snapshot.contextWindowTokens(),
        snapshot.reasoningEffort(), snapshot.textVerbosity(),
        WorkflowPrompts.instructions(snapshot.role(), snapshot.promptAddendum()),
        workflow.createdBy(), now);
    stage.useProviderInstance(snapshot.providerInstanceId());
    AgentRun run = runs.save(stage);
    turns.save(new AgentTurn(UUID.randomUUID(), run.id(), 0, AgentTurnKind.USER_PROMPT,
        null, null, null, safePrompt, now));
    auditLog.record(auditActor, "WORKFLOW_STAGE_RUN_CREATED", "AGENT_RUN", run.id(),
        "Created " + snapshot.role() + " run for workflow " + workflow.id());
    return view(run, turns.findByRunIdOrderByIndexAsc(run.id()));
  }

  @Transactional(readOnly = true)
  public List<AgentRunView> workflowRuns(UUID workflowId) {
    return runs.findByWorkflowIdOrderByCreatedAtAsc(workflowId).stream()
        .map(run -> view(run, turns.findByRunIdOrderByIndexAsc(run.id()))).toList();
  }

  @Transactional(readOnly = true)
  public AgentChatView chat(UUID chatId) {
    AgentChat chat = requireChat(chatId);
    return chatView(chat);
  }

  @Transactional(readOnly = true)
  public AgentChatView chat(UUID chatId, UUID actorId) {
    AgentChat chat = requireChat(chatId);
    if (!chat.createdBy().equals(actorId)) throw new IllegalArgumentException("Chat belongs to another creator");
    return chatView(chat);
  }

  @Transactional(readOnly = true)
  public List<AgentChatView> listChats() {
    return chats.findAllByOrderByUpdatedAtDesc().stream().map(this::chatView).toList();
  }

  @Transactional(readOnly = true)
  public List<AgentChatView> listChats(UUID actorId) {
    return chats.findByCreatedByOrderByUpdatedAtDesc(actorId).stream().map(this::chatView).toList();
  }

  @Transactional(readOnly = true)
  public List<AgentChatView> listChats(UUID worktreeId, UUID actorId) {
    return chats.findByWorktreeIdAndCreatedByOrderByUpdatedAtDesc(worktreeId, actorId).stream()
        .map(this::chatView)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<AgentRunView> chatRuns(UUID chatId) {
    requireChat(chatId);
    return runs.findByChatIdAndWorkflowIdIsNullOrderByChatMessageIndexAsc(chatId).stream()
        .map(run -> view(run, turns.findByRunIdOrderByIndexAsc(run.id()))).toList();
  }

  @Transactional(readOnly = true)
  public List<AgentChatSummaryView> chatSummaries(UUID chatId) {
    requireChat(chatId);
    return summaries.findByChatIdOrderByThroughMessageIndexAsc(chatId).stream()
        .map(summary -> new AgentChatSummaryView(
            summary.id(), summary.fromMessageIndex(), summary.throughMessageIndex(),
            summary.content(), summary.provider(), summary.model(), summary.createdAt()))
        .toList();
  }

  @Transactional
  public void resumeChatQueue(UUID chatId, UUID actorId, boolean overrideApprovalGate) {
    AgentChat chat = lockedChat(chatId);
    if (!chat.createdBy().equals(actorId)) throw new IllegalArgumentException("Chat belongs to another creator");
    chat.resumeQueue(overrideApprovalGate, Instant.now(clock));
  }

  @Transactional
  public void pauseChatQueue(UUID chatId) {
    lockedChat(chatId).pauseQueue(Instant.now(clock));
  }

  @Transactional
  public AgentChatView updateAutomation(
      UUID chatId, UUID actorId, boolean autoApproveChanges, boolean autoApproveTests) {
    AgentChat chat = lockedChat(chatId);
    if (!chat.createdBy().equals(actorId)) throw new IllegalArgumentException("Chat belongs to another creator");
    chat.setAutomation(autoApproveChanges, autoApproveTests, Instant.now(clock));
    return chatView(chat);
  }

  @Transactional(readOnly = true)
  public AgentRunView get(UUID runId, UUID expectedWorktreeId) {
    AgentRun run = requireRun(runId);
    requireWorktree(run, expectedWorktreeId);
    return view(run, turns.findByRunIdOrderByIndexAsc(run.id()));
  }

  @Transactional(readOnly = true)
  public AgentRunView get(UUID runId) {
    AgentRun run = requireRun(runId);
    return view(run, turns.findByRunIdOrderByIndexAsc(run.id()));
  }

  @Transactional(readOnly = true)
  public List<AgentRunView> list(UUID worktreeId) {
    return list(worktreeId, false);
  }

  @Transactional(readOnly = true)
  public List<AgentRunView> list(UUID worktreeId, boolean includeWorkflowStages) {
    worktrees.requireActiveWorktree(worktreeId);
    List<AgentRun> values = includeWorkflowStages
        ? runs.findByWorktreeIdOrderByCreatedAtDesc(worktreeId)
        : runs.findByWorktreeIdAndWorkflowIdIsNullOrderByCreatedAtDesc(worktreeId);
    return values.stream()
        .map(run -> view(run, List.of()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<AgentRunView> listAll() {
    return runs.findByWorkflowIdIsNullOrderByCreatedAtDesc().stream()
        .map(run -> view(run, List.of()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<AgentRunView> listAll(UUID actorId) {
    return listAll(actorId, false);
  }

  @Transactional(readOnly = true)
  public List<AgentRunView> listAll(UUID actorId, boolean includeWorkflowStages) {
    List<AgentRun> values = includeWorkflowStages
        ? runs.findByCreatedByOrderByCreatedAtDesc(actorId)
        : runs.findByCreatedByAndWorkflowIdIsNullOrderByCreatedAtDesc(actorId);
    return values.stream()
        .map(run -> view(run, List.of()))
        .toList();
  }

  /** The user's runs of one project, most recent first, bounded for display. */
  @Transactional(readOnly = true)
  public List<AgentRunView> listForProject(
      UUID projectId, UUID actorId, boolean includeWorkflowStages) {
    return runs.findForProject(projectId, actorId, includeWorkflowStages).stream()
        .limit(200)
        .map(run -> view(run, List.of()))
        .toList();
  }

  @Transactional(readOnly = true)
  public boolean hasActiveForWorktree(UUID worktreeId) {
    return runs.existsByWorktreeIdAndStatusIn(worktreeId, java.util.EnumSet.of(
        AgentRunStatus.PENDING, AgentRunStatus.RUNNING,
        AgentRunStatus.CANCELLATION_REQUESTED));
  }

  @Transactional
  public AgentRunView requestCancellation(
      UUID runId, UUID expectedCreatorId, String auditActor) {
    AgentRun run = locked(runId);
    if (!run.createdBy().equals(expectedCreatorId)) {
      throw new IllegalArgumentException("Agent run belongs to another creator");
    }
    if (!run.terminal()) {
      Instant now = Instant.now(clock);
      run.requestCancellation(now);
      // A run whose executor is gone (restart, crash) would stay "stopping" forever, because only
      // the executor acknowledges a cancellation. Finish it here when nobody holds its lease.
      if (run.status() == AgentRunStatus.CANCELLATION_REQUESTED
          && run.executionLeaseExpired(now, ABANDONED_AFTER)) {
        run.cancelled(now);
      }
      auditLog.record(
          auditActor, "AGENT_RUN_CANCELLATION_REQUESTED", "AGENT_RUN", run.id(),
          "Requested cancellation of agent run");
    }
    return view(run, turns.findByRunIdOrderByIndexAsc(run.id()));
  }

  private AgentRun requireRun(UUID id) {
    return runs.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Unknown agent run"));
  }

  private AgentChat requireChat(UUID id) {
    return chats.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown chat"));
  }

  private AgentChat lockedChat(UUID id) {
    return chats.findForUpdateById(id).orElseThrow(() -> new IllegalArgumentException("Unknown chat"));
  }

  private AgentRun locked(UUID id) {
    return runs.findForUpdateById(id)
        .orElseThrow(() -> new IllegalArgumentException("Unknown agent run"));
  }

  private static void requireWorktree(AgentRun run, UUID expectedWorktreeId) {
    if (!run.worktreeId().equals(expectedWorktreeId)) {
      throw new IllegalArgumentException("Agent run belongs to another worktree");
    }
  }

  private static String required(String value, String label, int maxLength) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
    if (value.length() > maxLength) throw new IllegalArgumentException(label + " is too long");
    return value;
  }

  private AgentRun createRun(
      AgentChat chat, String prompt, ModelProvider provider, UUID providerInstanceId, String model,
      int maxTurns, int maxOutputTokens, int contextWindowTokens, tech.wenisch.petri.agent.model.ReasoningEffort reasoning,
      tech.wenisch.petri.agent.model.TextVerbosity verbosity, UUID actorId, Instant now) {
    AgentRun created = new AgentRun(
        UUID.randomUUID(), chat.id(), chat.nextMessageIndex(now), chat.worktreeId(), provider, model,
        prompt, maxTurns, maxOutputTokens, contextWindowTokens, reasoning, verbosity, actorId, now);
    created.useProviderInstance(providerInstanceId);
    AgentRun run = runs.save(created);
    turns.save(new AgentTurn(UUID.randomUUID(), run.id(), 0, AgentTurnKind.USER_PROMPT,
        null, null, null, prompt, now));
    return run;
  }

  private AgentChatView chatView(AgentChat chat) {
    List<AgentRunView> values = runs.findByChatIdAndWorkflowIdIsNullOrderByChatMessageIndexAsc(chat.id()).stream()
        .map(run -> view(run, List.of())).toList();
    boolean active = values.stream().anyMatch(run -> !terminal(run.status()));
    int queued = (int) values.stream().filter(run -> run.status() == AgentRunStatus.PENDING).count();
    return new AgentChatView(chat.id(), chat.worktreeId(), chat.title(), chat.queuePaused(),
        chat.approvalGateOverridden(), chat.autoApproveChanges(), chat.autoApproveTests(),
        chat.executionMode(), chat.parentChatId(), chat.forkedFromWorkflowId(), queued, active, chat.createdBy(), chat.createdAt(),
        chat.updatedAt(), values);
  }

  private static boolean terminal(AgentRunStatus status) {
    return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED
        || status == AgentRunStatus.BUDGET_EXHAUSTED || status == AgentRunStatus.CANCELLED;
  }

  private static String title(String prompt) {
    String normalized = prompt.strip().replaceAll("\\s+", " ");
    return normalized.length() <= 160 ? normalized : normalized.substring(0, 157) + "...";
  }

  private static void validateContextWindow(int contextWindowTokens, int maxOutputTokens) {
    if (contextWindowTokens > 10_000_000
        || contextWindowTokens < maxOutputTokens + 2048
        || Math.floor(contextWindowTokens * 0.85) < maxOutputTokens + 2048) {
      throw new IllegalArgumentException(
          "Context window must leave at least 2048 input tokens after the safety reserve");
    }
  }

  private static AgentRunView view(AgentRun run, List<AgentTurn> turns) {
    return new AgentRunView(
        run.id(), run.worktreeId(), run.provider(), run.providerInstanceId(), run.model(), run.prompt(),
        run.status(), run.maxModelTurns(),
        run.maxOutputTokens(), run.reasoningEffort(), run.textVerbosity(), run.modelTurnCount(),
        run.finalOutput(), run.errorMessage(), run.createdBy(), run.createdAt(),
        run.startedAt(), run.completedAt(), turns.stream().map(AgentRunService::view).toList(),
        run.chatId(), run.chatMessageIndex(), run.contextWindowTokens(), run.partialOutput(),
        run.workflowId(), run.workflowRole(), run.workflowPhase(), run.workflowAttempt(),
        run.loopGuardNudges(), run.loopGuardWithdrawals(), run.automationRunId());
  }

  private static AgentTurnView view(AgentTurn turn) {
    return new AgentTurnView(
        turn.id(), turn.index(), turn.kind(), turn.providerResponseId(), turn.toolCallId(),
        turn.toolName(), turn.content(), turn.createdAt());
  }
}
