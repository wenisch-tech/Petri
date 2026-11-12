package tech.wenisch.petri.workspace;

import java.nio.file.Path;
import java.util.UUID;

public interface WorktreeLocator {
  /** Validates that the worktree is active and returns its host path. */
  Path requireActiveWorktree(UUID worktreeId);

  /** As above, also returning the project and the host that must be used to act on the tree. */
  ActiveWorktree resolveActiveWorktree(UUID worktreeId);
}
