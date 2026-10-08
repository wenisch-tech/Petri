package tech.wenisch.petri.agent.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tech.wenisch.petri.agent.model.ModelProvider;

public record AgentRunView(
    UUID id,
    UUID worktreeId,
    ModelProvider provider,
    String model,
    AgentRunStatus status,
    int maxModelTurns,
    int modelTurnCount,
    String finalOutput,
    String errorMessage,
    UUID createdBy,
    Instant createdAt,
    Instant startedAt,
    Instant completedAt,
    List<AgentTurnView> turns) {}
