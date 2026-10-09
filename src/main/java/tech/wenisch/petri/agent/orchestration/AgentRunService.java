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

@Service
public class AgentRunService {
  private static final int MAX_PROMPT_LENGTH = 200_000;
  private static final int DEFAULT_MAX_MODEL_TURNS = 20;

  private final AgentRunRepository runs;
  private final AgentTurnRepository turns;
  private final ModelCredentialService credentials;
  private final AppUserRepository users;
  private final WorktreeLocator worktrees;
  private final AuditLogService auditLog;
  private final Clock clock;

  @Autowired
  public AgentRunService(
      AgentRunRepository runs,
      AgentTurnRepository turns,
      ModelCredentialService credentials,
      AppUserRepository users,
      WorktreeLocator worktrees,
      AuditLogService auditLog) {
    this(runs, turns, credentials, users, worktrees, auditLog, Clock.systemUTC());
  }

  AgentRunService(
      AgentRunRepository runs,
      AgentTurnRepository turns,
      ModelCredentialService credentials,
      AppUserRepository users,
      WorktreeLocator worktrees,
      AuditLogService auditLog,
      Clock clock) {
    this.runs = runs;
    this.turns = turns;
    this.credentials = credentials;
    this.users = users;
    this.worktrees = worktrees;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional
  public AgentRunView create(
      UUID worktreeId,
      CreateAgentRunCommand command,
      UUID actorId,
      String auditActor) {
    worktrees.requireActiveWorktree(worktreeId);
    if (!users.existsById(actorId)) throw new IllegalArgumentException("Unknown run creator");
    String prompt = required(command.prompt(), "Prompt", MAX_PROMPT_LENGTH);
    ModelProvider provider = command.provider() == null ? ModelProvider.OPENAI : command.provider();
    ModelCredentialStatus credential = credentials.status(provider);
    if (!credential.configured()) {
      throw new IllegalStateException(provider + " credentials are not configured");
    }
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
    Instant now = Instant.now(clock);
    AgentRun run = runs.save(new AgentRun(
        UUID.randomUUID(),
        worktreeId,
        provider,
        model,
        prompt,
        maxTurns,
        maxOutputTokens,
        command.reasoningEffort() == null ? credential.reasoningEffort() : command.reasoningEffort(),
        command.textVerbosity() == null ? credential.textVerbosity() : command.textVerbosity(),
        actorId,
        now));
    turns.save(new AgentTurn(
        UUID.randomUUID(), run.id(), 0, AgentTurnKind.USER_PROMPT,
        null, null, null, prompt, now));
    auditLog.record(
        auditActor, "AGENT_RUN_CREATED", "AGENT_RUN", run.id(),
        "Created agent run for worktree " + worktreeId);
    return view(run, turns.findByRunIdOrderByIndexAsc(run.id()));
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
    worktrees.requireActiveWorktree(worktreeId);
    return runs.findByWorktreeIdOrderByCreatedAtDesc(worktreeId).stream()
        .map(run -> view(run, List.of()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<AgentRunView> listAll() {
    return runs.findAllByOrderByCreatedAtDesc().stream()
        .map(run -> view(run, List.of()))
        .toList();
  }

  @Transactional
  public AgentRunView requestCancellation(
      UUID runId, UUID expectedCreatorId, String auditActor) {
    AgentRun run = locked(runId);
    if (!run.createdBy().equals(expectedCreatorId)) {
      throw new IllegalArgumentException("Agent run belongs to another creator");
    }
    if (!run.terminal()) {
      run.requestCancellation(Instant.now(clock));
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

  private static AgentRunView view(AgentRun run, List<AgentTurn> turns) {
    return new AgentRunView(
        run.id(), run.worktreeId(), run.provider(), run.model(), run.prompt(), run.status(), run.maxModelTurns(),
        run.maxOutputTokens(), run.reasoningEffort(), run.textVerbosity(), run.modelTurnCount(),
        run.finalOutput(), run.errorMessage(), run.createdBy(), run.createdAt(),
        run.startedAt(), run.completedAt(), turns.stream().map(AgentRunService::view).toList());
  }

  private static AgentTurnView view(AgentTurn turn) {
    return new AgentTurnView(
        turn.id(), turn.index(), turn.kind(), turn.providerResponseId(), turn.toolCallId(),
        turn.toolName(), turn.content(), turn.createdAt());
  }
}
