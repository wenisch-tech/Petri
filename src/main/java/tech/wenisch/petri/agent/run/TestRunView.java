package tech.wenisch.petri.agent.run;

import java.time.Instant;
import java.util.UUID;

public record TestRunView(
    UUID id,
    UUID worktreeId,
    UUID agentRunId,
    TestBuildSystem buildSystem,
    String command,
    TestRunStatus status,
    Integer exitCode,
    String output,
    boolean outputTruncated,
    Instant requestedAt,
    Instant approvedAt,
    Instant startedAt,
    Instant completedAt,
    UUID sandboxProfileId,
    String sandboxProfileName,
    String sandboxImage,
    String sandboxImageDigest,
    Boolean sandboxNetworkEnabled) {}
