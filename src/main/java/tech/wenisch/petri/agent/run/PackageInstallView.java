package tech.wenisch.petri.agent.run;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PackageInstallView(
    UUID id,
    UUID worktreeId,
    UUID agentRunId,
    List<String> packages,
    String reason,
    PackageInstallStatus status,
    Integer exitCode,
    String output,
    boolean outputTruncated,
    Instant requestedAt,
    Instant approvedAt,
    Instant startedAt,
    Instant completedAt) {}
