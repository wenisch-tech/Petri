package tech.wenisch.petri.workspace;

import java.nio.file.Path;
import java.util.UUID;

/** An active worktree together with the host that holds it. */
public record ActiveWorktree(UUID id, UUID projectId, Path root, WorkspaceHost host) {}
