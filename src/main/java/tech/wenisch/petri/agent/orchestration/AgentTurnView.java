package tech.wenisch.petri.agent.orchestration;

import java.time.Instant;
import java.util.UUID;

public record AgentTurnView(
    UUID id,
    int index,
    AgentTurnKind kind,
    String providerResponseId,
    String toolCallId,
    String toolName,
    String content,
    Instant createdAt) {}
