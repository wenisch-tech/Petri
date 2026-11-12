package tech.wenisch.petri.workspace;

import java.util.UUID;

/** Chooses the host a project's workspace lives on. */
public interface WorkspaceHostResolver {
  WorkspaceHost forProject(UUID projectId);

  WorkspaceHost forWorktree(UUID worktreeId);
}
