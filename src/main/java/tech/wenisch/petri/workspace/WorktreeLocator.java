package tech.wenisch.petri.workspace;

import java.nio.file.Path;
import java.util.UUID;

public interface WorktreeLocator {
  Path requireActiveWorktree(UUID worktreeId);
}
