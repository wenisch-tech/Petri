package tech.wenisch.petri.agent.run;

import java.time.Instant;
import java.util.UUID;

public record TestRunView(
    UUID id,
    UUID worktreeId,
    TestBuildSystem buildSystem,
    String command,
    TestRunStatus status,
    Integer exitCode,
    String output,
    boolean outputTruncated,
    Instant requestedAt,
    Instant approvedAt,
    Instant startedAt,
    Instant completedAt) {}
