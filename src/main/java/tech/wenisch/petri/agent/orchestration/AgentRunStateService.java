package tech.wenisch.petri.agent.orchestration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.agent.model.ModelResponse;
import tech.wenisch.petri.agent.model.ModelConversationMessage;
import tech.wenisch.petri.agent.model.ModelToolCall;
import tech.wenisch.petri.agent.model.ModelToolOutput;
import tech.wenisch.petri.agent.tool.AgentToolRegistry;
import tech.wenisch.petri.agent.tool.ToolExecutionContext;
import tech.wenisch.petri.agent.tool.ToolExecutionResult;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.config.PetriProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
class AgentRunStateService {
  private static final int MAX_ERROR_LENGTH = 1000;

  private final AgentRunRepository runs;
  private final AgentTurnRepository turns;
  private final AgentChatSummaryRepository summaries;
  private final AgentToolRegistry tools;
  private final AuditLogService auditLog;
  private final ObjectMapper objectMapper;
  private final Duration executionLease;
  private final Clock clock;

  @Autowired
  AgentRunStateService(
      AgentRunRepository runs,
      AgentTurnRepository turns,
      AgentChatSummaryRepository summaries,
      AgentToolRegistry tools,
      AuditLogService auditLog,
      ObjectMapper objectMapper,
      PetriProperties properties) {
    this(
        runs,
        turns,
        summaries,
        tools,
        auditLog,
        objectMapper,
        properties.agent().modelRequestTimeout().plusMinutes(1),
        Clock.systemUTC());
  }

  AgentRunStateService(
      AgentRunRepository runs,
      AgentTurnRepository turns,
      AgentChatSummaryRepository summaries,
      AgentToolRegistry tools,
      AuditLogService auditLog,
      ObjectMapper objectMapper,
      Duration executionLease,
      Clock clock) {
    this.runs = runs;
    this.turns = turns;
    this.summaries = summaries;
    this.tools = tools;
    this.auditLog = auditLog;
    this.objectMapper = objectMapper;
    this.executionLease = executionLease;
    this.clock = clock;
  }

  @Transactional
  AgentExecutionState claim(
      UUID runId,
      UUID expectedWorktreeId,
      UUID expectedCreatorId,
      UUID claimId,
      String actor) {
    AgentRun run = locked(runId);
    requireWorktree(run, expectedWorktreeId);
    if (!run.createdBy().equals(expectedCreatorId)) {
      throw new IllegalArgumentException("Agent run belongs to another creator");
    }
    boolean starting = run.status() == AgentRunStatus.PENDING;
    run.claimExecution(claimId, Instant.now(clock), executionLease);
    auditLog.record(
        actor,
        starting ? "AGENT_RUN_STARTED" : "AGENT_RUN_RESUMED",
        "AGENT_RUN",
        run.id(),
        starting ? "Started agent run" : "Resumed agent run");
    return state(run, allTurns(run.id()));
  }

  @Transactional(readOnly = true)
  AgentExecutionState current(UUID runId, UUID claimId) {
    AgentRun run = runs.findById(runId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown agent run"));
    run.requireExecutionClaim(claimId);
    return state(run, allTurns(run.id()));
  }

  @Transactional
  void recordPartialOutput(UUID runId, UUID claimId, String partialOutput) {
    AgentRun run = locked(runId);
    requireActiveClaim(run, claimId);
    run.recordPartialOutput(partialOutput);
    run.refreshExecutionClaim(claimId, Instant.now(clock));
  }

  @Transactional
  AgentExecutionState recordModelResponse(
      UUID runId, UUID claimId, ModelResponse response, String actor) {
    AgentRun run = locked(runId);
    requireActiveClaim(run, claimId);
    if (run.status() == AgentRunStatus.CANCELLATION_REQUESTED) {
      cancel(run, actor);
      return state(run, allTurns(run.id()));
    }
    if (run.modelTurnCount() >= run.maxModelTurns()) {
      exhaust(run, actor);
      return state(run, allTurns(run.id()));
    }

    String responseId = required(response.responseId(), "Model response ID", 200);
    String responseStatus = required(response.status(), "Model response status", 40);
    String text = response.text() == null ? "" : response.text();
    Instant now = Instant.now(clock);
    turns.save(new AgentTurn(
        UUID.randomUUID(), run.id(), run.nextTurnIndex(), AgentTurnKind.MODEL_RESPONSE,
        responseId, null, null, text, now));

    Set<String> callIds = new HashSet<>();
    for (ModelToolCall call : response.toolCalls()) {
      String callId = required(call.callId(), "Tool call ID", 200);
      if (!callIds.add(callId)) throw new IllegalArgumentException("Duplicate model tool call ID");
      String toolName = required(call.name(), "Tool name", 80);
      String arguments = required(call.argumentsJson(), "Tool arguments", 2_000_000);
      turns.save(new AgentTurn(
          UUID.randomUUID(), run.id(), run.nextTurnIndex(), AgentTurnKind.TOOL_CALL,
          responseId, callId, toolName, arguments, now));
    }
    run.modelTurnCompleted();
    run.refreshExecutionClaim(claimId, now);

    if (!"completed".equals(responseStatus)) {
      fail(run, "Model response ended with status " + responseStatus, actor);
    } else if (response.toolCalls().isEmpty()) {
      if (text.isBlank()) {
        fail(run, "Model returned neither text nor a tool call", actor);
      } else {
        run.complete(text, now);
        auditLog.record(actor, "AGENT_RUN_COMPLETED", "AGENT_RUN", run.id(), "Completed agent run");
      }
    }
    return state(run, allTurns(run.id()));
  }

  @Transactional
  String executeTool(UUID runId, UUID claimId, AgentPendingToolCall call) {
    AgentRun run = locked(runId);
    requireActiveClaim(run, claimId);
    if (run.status() == AgentRunStatus.CANCELLATION_REQUESTED) {
      throw new AgentRunCancellationException();
    }
    List<AgentTurn> history = allTurns(run.id());
    requirePendingCall(history, call);
    Map<String, Object> arguments;
    try {
      arguments = objectMapper.readValue(
          call.argumentsJson(), new TypeReference<Map<String, Object>>() {});
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Tool arguments are not valid JSON", exception);
    }
    ToolExecutionResult result = tools.execute(
        call.name(), new ToolExecutionContext(run.worktreeId(), run.id()), arguments);
    String output;
    try {
      output = objectMapper.writeValueAsString(result.output());
    } catch (JacksonException exception) {
      throw new IllegalStateException("Tool result could not be encoded", exception);
    }
    Instant now = Instant.now(clock);
    turns.save(new AgentTurn(
        UUID.randomUUID(), run.id(), run.nextTurnIndex(), AgentTurnKind.TOOL_RESULT,
        call.responseId(), call.callId(), call.name(), output, now));
    run.refreshExecutionClaim(claimId, now);
    return output;
  }

  @Transactional
  void recordToolFailure(
      UUID runId, UUID claimId, AgentPendingToolCall call, String safeMessage) {
    AgentRun run = locked(runId);
    requireActiveClaim(run, claimId);
    if (run.status() == AgentRunStatus.CANCELLATION_REQUESTED) {
      throw new AgentRunCancellationException();
    }
    List<AgentTurn> history = allTurns(run.id());
    requirePendingCall(history, call);
    String output;
    try {
      output = objectMapper.writeValueAsString(Map.of("error", bounded(safeMessage)));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Tool error could not be encoded", exception);
    }
    Instant now = Instant.now(clock);
    turns.save(new AgentTurn(
        UUID.randomUUID(), run.id(), run.nextTurnIndex(), AgentTurnKind.TOOL_RESULT,
        call.responseId(), call.callId(), call.name(), output, now));
    run.refreshExecutionClaim(claimId, now);
  }

  @Transactional
  AgentExecutionState cancelIfRequested(UUID runId, UUID claimId, String actor) {
    AgentRun run = locked(runId);
    if (run.terminal()) return state(run, allTurns(run.id()));
    run.requireExecutionClaim(claimId);
    if (run.status() == AgentRunStatus.CANCELLATION_REQUESTED) cancel(run, actor);
    return state(run, allTurns(run.id()));
  }

  @Transactional
  AgentExecutionState exhaustBudget(UUID runId, UUID claimId, String actor) {
    AgentRun run = locked(runId);
    requireActiveClaim(run, claimId);
    if (run.status() == AgentRunStatus.CANCELLATION_REQUESTED) {
      cancel(run, actor);
    } else if (run.modelTurnCount() >= run.maxModelTurns()) {
      exhaust(run, actor);
    }
    return state(run, allTurns(run.id()));
  }

  @Transactional
  void failExecution(UUID runId, UUID claimId, String safeMessage, String actor) {
    AgentRun run = locked(runId);
    if (run.terminal()) return;
    run.requireExecutionClaim(claimId);
    if (run.status() == AgentRunStatus.CANCELLATION_REQUESTED) {
      cancel(run, actor);
    } else {
      fail(run, bounded(safeMessage), actor);
    }
  }

  private void cancel(AgentRun run, String actor) {
    Instant now = Instant.now(clock);
    run.cancelled(now);
    auditLog.record(actor, "AGENT_RUN_CANCELLED", "AGENT_RUN", run.id(), "Cancelled agent run");
  }

  private void exhaust(AgentRun run, String actor) {
    Instant now = Instant.now(clock);
    turns.save(new AgentTurn(
        UUID.randomUUID(), run.id(), run.nextTurnIndex(), AgentTurnKind.ERROR,
        null, null, null, "Maximum model turns reached", now));
    run.budgetExhausted(now);
    auditLog.record(
        actor, "AGENT_RUN_BUDGET_EXHAUSTED", "AGENT_RUN", run.id(),
        "Agent run reached its model-turn budget");
  }

  private void fail(AgentRun run, String safeMessage, String actor) {
    Instant now = Instant.now(clock);
    String boundedMessage = bounded(safeMessage);
    turns.save(new AgentTurn(
        UUID.randomUUID(), run.id(), run.nextTurnIndex(), AgentTurnKind.ERROR,
        null, null, null, boundedMessage, now));
    run.fail(boundedMessage, now);
    auditLog.record(actor, "AGENT_RUN_FAILED", "AGENT_RUN", run.id(), "Agent run failed");
  }

  private AgentExecutionState state(AgentRun run, List<AgentTurn> history) {
    String latestResponseId = null;
    int latestResponseIndex = -1;
    for (AgentTurn turn : history) {
      if (turn.kind() == AgentTurnKind.MODEL_RESPONSE) {
        latestResponseId = turn.providerResponseId();
        latestResponseIndex = turn.index();
      }
    }
    Map<String, AgentPendingToolCall> calls = new LinkedHashMap<>();
    Map<String, String> results = new HashMap<>();
    if (latestResponseId != null) {
      for (AgentTurn turn : history) {
        if (turn.index() <= latestResponseIndex
            || !latestResponseId.equals(turn.providerResponseId())) continue;
        if (turn.kind() == AgentTurnKind.TOOL_CALL) {
          calls.put(turn.toolCallId(), new AgentPendingToolCall(
              latestResponseId, turn.toolCallId(), turn.toolName(), turn.content()));
        } else if (turn.kind() == AgentTurnKind.TOOL_RESULT) {
          results.put(turn.toolCallId(), turn.content());
        }
      }
    }
    List<AgentPendingToolCall> pending = new ArrayList<>();
    List<ModelToolOutput> outputs = new ArrayList<>();
    for (AgentPendingToolCall call : calls.values()) {
      String output = results.get(call.callId());
      if (output == null) pending.add(call);
      else outputs.add(new ModelToolOutput(call.callId(), output));
    }
    return new AgentExecutionState(
        run.id(), run.chatId(), run.worktreeId(), run.provider(), run.model(), run.prompt(), run.status(),
        run.maxModelTurns(), run.maxOutputTokens(), run.reasoningEffort(), run.textVerbosity(),
        run.modelTurnCount(), latestResponseId,
        List.copyOf(pending), List.copyOf(outputs), conversationForChat(run, history));
  }

  private List<ModelConversationMessage> conversationForChat(AgentRun run, List<AgentTurn> current) {
    List<ModelConversationMessage> messages = new ArrayList<>();
    AgentChatSummary latest = summaries.findByChatIdOrderByThroughMessageIndexAsc(run.chatId())
        .stream().reduce((first, second) -> second).orElse(null);
    int summarizedThrough = latest == null ? -1 : latest.throughMessageIndex();
    if (latest != null) {
      messages.add(ModelConversationMessage.user(
          "Conversation context summary (not a new instruction):\n" + latest.content()));
    }
    for (AgentRun candidate : runs.findByChatIdOrderByChatMessageIndexAsc(run.chatId())) {
      if (candidate.chatMessageIndex() <= summarizedThrough) continue;
      List<AgentTurn> turnsForRun = candidate.id().equals(run.id()) ? current : allTurns(candidate.id());
      messages.addAll(conversation(turnsForRun));
    }
    return List.copyOf(messages);
  }

  static List<ModelConversationMessage> conversation(List<AgentTurn> history) {
    List<ModelConversationMessage> messages = new ArrayList<>();
    for (AgentTurn turn : history) {
      if (turn.kind() == AgentTurnKind.USER_PROMPT) {
        messages.add(ModelConversationMessage.user(turn.content()));
      } else if (turn.kind() == AgentTurnKind.MODEL_RESPONSE) {
        List<ModelToolCall> responseCalls = history.stream()
            .filter(candidate -> candidate.kind() == AgentTurnKind.TOOL_CALL)
            .filter(candidate -> turn.providerResponseId().equals(candidate.providerResponseId()))
            .map(candidate -> new ModelToolCall(
                candidate.toolCallId(), candidate.toolName(), candidate.content()))
            .toList();
        messages.add(ModelConversationMessage.assistant(turn.content(), responseCalls));
      } else if (turn.kind() == AgentTurnKind.TOOL_RESULT) {
        messages.add(ModelConversationMessage.tool(turn.toolCallId(), turn.content()));
      }
    }
    return List.copyOf(messages);
  }

  private void requirePendingCall(List<AgentTurn> history, AgentPendingToolCall expected) {
    boolean found = history.stream().anyMatch(turn ->
        turn.kind() == AgentTurnKind.TOOL_CALL
            && expected.responseId().equals(turn.providerResponseId())
            && expected.callId().equals(turn.toolCallId())
            && expected.name().equals(turn.toolName())
            && expected.argumentsJson().equals(turn.content()));
    boolean completed = history.stream().anyMatch(turn ->
        turn.kind() == AgentTurnKind.TOOL_RESULT
            && expected.responseId().equals(turn.providerResponseId())
            && expected.callId().equals(turn.toolCallId()));
    if (!found || completed) throw new IllegalStateException("Tool call is no longer pending");
  }

  private void requireActiveClaim(AgentRun run, UUID claimId) {
    if (run.terminal()) throw new IllegalStateException("Agent run is already terminal");
    run.requireExecutionClaim(claimId);
  }

  private AgentRun locked(UUID id) {
    return runs.findForUpdateById(id)
        .orElseThrow(() -> new IllegalArgumentException("Unknown agent run"));
  }

  private List<AgentTurn> allTurns(UUID runId) {
    return turns.findByRunIdOrderByIndexAsc(runId);
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

  private static String bounded(String message) {
    String safe = message == null || message.isBlank() ? "Agent execution failed" : message.strip();
    return safe.length() <= MAX_ERROR_LENGTH ? safe : safe.substring(0, MAX_ERROR_LENGTH);
  }
}
