package tech.wenisch.petri.agent.orchestration;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.agent.model.ModelClientRegistry;
import tech.wenisch.petri.agent.model.ModelRequest;
import tech.wenisch.petri.agent.model.ModelResponse;

@Service
class AgentChatSummaryService {
  /** Instructions and the available tool schemas are deliberately charged conservatively. */
  private static final int REQUEST_OVERHEAD_TOKENS = 12_000;
  private static final String INSTRUCTIONS = """
      Summarize this coding-agent conversation for a later turn. Preserve the user's goals,
      decisions, relevant files and behavior, proposed or applied changes, test outcomes and
      unresolved approvals. Be concise, factual, and do not introduce new instructions.
      """;

  private final AgentRunRepository runs;
  private final AgentTurnRepository turns;
  private final AgentChatSummaryRepository summaries;
  private final ModelClientRegistry clients;
  private final Clock clock;

  @Autowired
  AgentChatSummaryService(
      AgentRunRepository runs,
      AgentTurnRepository turns,
      AgentChatSummaryRepository summaries,
      ModelClientRegistry clients) {
    this(runs, turns, summaries, clients, Clock.systemUTC());
  }

  AgentChatSummaryService(
      AgentRunRepository runs,
      AgentTurnRepository turns,
      AgentChatSummaryRepository summaries,
      ModelClientRegistry clients,
      Clock clock) {
    this.runs = runs;
    this.turns = turns;
    this.summaries = summaries;
    this.clients = clients;
    this.clock = clock;
  }

  @Transactional
  boolean compactIfNeeded(AgentExecutionState state) {
    AgentRun current = runs.findById(state.runId())
        .orElseThrow(() -> new IllegalArgumentException("Unknown agent run"));
    List<AgentRun> history = runs.findByChatIdAndWorkflowIdIsNullOrderByChatMessageIndexAsc(current.chatId());
    int inputBudget = (int) Math.floor(current.contextWindowTokens() * 0.85)
        - current.maxOutputTokens();
    if (inputBudget < 2048) {
      throw new IllegalArgumentException("Context window leaves too little input budget");
    }
    List<AgentChatSummary> revisions = summaries.findByChatIdOrderByThroughMessageIndexAsc(current.chatId());
    AgentChatSummary latest = revisions.isEmpty() ? null : revisions.getLast();
    if (contextTokens(history, latest, Integer.MAX_VALUE) <= inputBudget) return false;
    List<AgentRun> earlier = history.stream()
        .filter(run -> run.chatMessageIndex() < current.chatMessageIndex()).toList();
    if (earlier.isEmpty()) {
      throw new IllegalArgumentException("Current prompt and agent context exceed the configured context window");
    }
    int through = earlier.size() > 1
        ? earlier.get(earlier.size() - 2).chatMessageIndex()
        : earlier.getFirst().chatMessageIndex();
    if (latest != null) through = Math.max(through, latest.throughMessageIndex() + 1);
    if (through >= current.chatMessageIndex()) {
      throw new IllegalArgumentException("Current prompt and agent context exceed the configured context window");
    }
    String source = sourceForRevision(history, latest, through);
    ModelResponse response = clients.require(current.provider()).respond(new ModelRequest(
        current.model(), INSTRUCTIONS, source, null, List.of(), List.of(),
        Math.min(2048, current.maxOutputTokens()), null, null));
    if (!"completed".equals(response.status()) || response.text() == null || response.text().isBlank()) {
      throw new IllegalStateException("Conversation context could not be summarized");
    }
    int from = revisions.isEmpty() ? 0 : revisions.getFirst().fromMessageIndex();
    AgentChatSummary revision = summaries.save(new AgentChatSummary(UUID.randomUUID(), current.chatId(), from, through,
        response.text(), current.provider(), current.model(), Instant.now(clock)));
    if (contextTokens(history, revision, Integer.MAX_VALUE) > inputBudget) {
      throw new IllegalArgumentException("Current prompt and agent context exceed the configured context window");
    }
    return true;
  }

  private String sourceForRevision(
      List<AgentRun> history, AgentChatSummary previous, int through) {
    StringBuilder value = new StringBuilder();
    if (previous != null) value.append("PRIOR SUMMARY:\n").append(previous.content()).append("\n\n");
    int after = previous == null ? -1 : previous.throughMessageIndex();
    for (AgentRun run : history) {
      if (run.chatMessageIndex() <= after || run.chatMessageIndex() > through) continue;
      value.append("USER: ").append(run.prompt()).append('\n');
      for (AgentTurn turn : turns.findByRunIdOrderByIndexAsc(run.id())) {
        if (turn.kind() == AgentTurnKind.MODEL_RESPONSE) value.append("ASSISTANT: ");
        else if (turn.kind() == AgentTurnKind.TOOL_RESULT) value.append("TOOL RESULT: ");
        else continue;
        value.append(turn.content()).append('\n');
      }
    }
    return value.toString();
  }

  private int contextTokens(List<AgentRun> history, AgentChatSummary summary, int through) {
    return REQUEST_OVERHEAD_TOKENS + estimateTokens(sourceForRevision(history, summary, through));
  }

  private static int estimateTokens(String value) {
    return Math.max(1, (int) Math.ceil(value.getBytes(StandardCharsets.UTF_8).length / 3.0));
  }
}
