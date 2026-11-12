package tech.wenisch.petri.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Directory layout of one workspace host, plus the structural checks that need the host's own
 * filesystem. Paths are host paths: real paths for a local host, POSIX paths inside the pod for a
 * remote one. Callers must not use {@code java.nio.file.Files} on them.
 */
public interface WorkspaceLayout {
  Path root() throws IOException;

  Path mirror(UUID projectId) throws IOException;

  Path runWorktree(UUID projectId, UUID worktreeId) throws IOException;

  Path temporaryDirectory() throws IOException;

  Path worktreeTemporaryDirectory(Path worktree) throws IOException;

  Path testCaches(UUID projectId) throws IOException;

  /** Creates a fresh private directory below the temporary directory. */
  Path createTemporaryDirectory(String prefix) throws IOException;

  /** Best-effort recursive removal; only paths below the temporary directories are touched. */
  void deleteTemporary(Path path);

  void removeWorktreeTemporaryDirectory(UUID worktreeId);

  Path requireExistingPath(Path path) throws IOException;

  HostStat stat(Path path) throws IOException;

  boolean isEmptyDirectory(Path directory) throws IOException;
}
