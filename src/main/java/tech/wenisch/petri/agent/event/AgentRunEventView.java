package tech.wenisch.petri.agent.event;

import java.time.Instant;
import java.util.UUID;

public record AgentRunEventView(
    UUID id,
    UUID worktreeId,
    AgentRunEventType type,
    String toolName,
    String summary,
    Instant occurredAt) {}
