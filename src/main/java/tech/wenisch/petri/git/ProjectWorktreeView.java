package tech.wenisch.petri.git;

import java.time.Instant;
import java.util.UUID;

public record ProjectWorktreeView(
    UUID id,
    UUID projectId,
    String gitRevision,
    ProjectWorktreeStatus status,
    Instant createdAt,
    Instant removedAt) {}
