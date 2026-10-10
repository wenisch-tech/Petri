package tech.wenisch.petri.git;

import java.time.Instant;
import java.util.UUID;

public record ProjectWorktreeView(
    UUID id,
    UUID projectId,
    String gitRevision,
    String branchName,
    String upstreamBranch,
    boolean detached,
    ProjectWorktreeStatus status,
    Instant createdAt,
    Instant removedAt) {}
