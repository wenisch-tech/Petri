package tech.wenisch.petri.agent.run;

import java.time.Instant;
import java.util.UUID;

public record TerminalCommandView(
    UUID id,
    UUID worktreeId,
    UUID agentRunId,
    String source,
    String command,
    String reason,
    TerminalCommandStatus status,
    Integer exitCode,
    String output,
    boolean outputTruncated,
    Instant requestedAt,
    Instant approvedAt,
    Instant startedAt,
    Instant completedAt) {}
