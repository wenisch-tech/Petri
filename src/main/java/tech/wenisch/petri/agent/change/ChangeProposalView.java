package tech.wenisch.petri.agent.change;

import java.time.Instant;
import java.util.UUID;

public record ChangeProposalView(
    UUID id,
    UUID worktreeId,
    UUID agentRunId,
    String filePath,
    ChangeType changeType,
    String expectedSha256,
    String replacementSha256,
    String unifiedDiff,
    ChangeProposalStatus status,
    Instant createdAt,
    Instant approvedAt,
    Instant appliedAt) {}
