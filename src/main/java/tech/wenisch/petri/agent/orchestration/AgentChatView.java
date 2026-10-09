package tech.wenisch.petri.agent.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentChatView(
    UUID id,
    UUID worktreeId,
    String title,
    boolean queuePaused,
    boolean approvalGateOverridden,
    int queuedRunCount,
    boolean active,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt,
    List<AgentRunView> runs) {}
