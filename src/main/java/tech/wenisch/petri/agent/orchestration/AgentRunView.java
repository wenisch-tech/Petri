package tech.wenisch.petri.agent.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.ReasoningEffort;
import tech.wenisch.petri.agent.model.TextVerbosity;

public record AgentRunView(
    UUID id,
    UUID worktreeId,
    ModelProvider provider,
    String model,
    String prompt,
    AgentRunStatus status,
    int maxModelTurns,
    int maxOutputTokens,
    ReasoningEffort reasoningEffort,
    TextVerbosity textVerbosity,
    int modelTurnCount,
    String finalOutput,
    String errorMessage,
    UUID createdBy,
    Instant createdAt,
    Instant startedAt,
    Instant completedAt,
    List<AgentTurnView> turns) {}
