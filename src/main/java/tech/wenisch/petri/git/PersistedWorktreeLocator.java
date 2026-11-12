package tech.wenisch.petri.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.workspace.ActiveWorktree;
import tech.wenisch.petri.workspace.HostStat;
import tech.wenisch.petri.workspace.WorkspaceHost;
import tech.wenisch.petri.workspace.WorkspaceHostResolver;
import tech.wenisch.petri.workspace.WorkspaceLayout;
import tech.wenisch.petri.workspace.WorktreeLocator;

@Component
class PersistedWorktreeLocator implements WorktreeLocator {
  private final ProjectWorktreeRepository worktrees;
  private final WorkspaceHostResolver hosts;

  PersistedWorktreeLocator(ProjectWorktreeRepository worktrees, WorkspaceHostResolver hosts) {
    this.worktrees = worktrees;
    this.hosts = hosts;
  }

  @Override
  @Transactional(readOnly = true)
  public Path requireActiveWorktree(UUID worktreeId) {
    return resolveActiveWorktree(worktreeId).root();
  }

  @Override
  @Transactional(readOnly = true)
  public ActiveWorktree resolveActiveWorktree(UUID worktreeId) {
    ProjectWorktree worktree = worktrees.findById(worktreeId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown worktree"));
    if (worktree.status() != ProjectWorktreeStatus.ACTIVE) {
      throw new IllegalStateException("Worktree is not active");
    }
    WorkspaceHost host = hosts.forProject(worktree.projectId());
    WorkspaceLayout layout = host.layout();
    try {
      Path expected = layout.runWorktree(worktree.projectId(), worktree.id());
      String expectedRelative = layout.root().relativize(expected).toString();
      if (!expectedRelative.equals(worktree.relativePath())) {
        throw new IllegalStateException("Recorded worktree path does not match its safe derived path");
      }
      HostStat stat = layout.stat(expected);
      if (stat.isSymlink()) {
        throw new IllegalStateException("Worktree root must not be a symbolic link");
      }
      if (!stat.isDirectory()) {
        throw new IllegalStateException("Worktree directory is missing");
      }
      return new ActiveWorktree(
          worktree.id(), worktree.projectId(), layout.requireExistingPath(expected), host);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not resolve worktree", exception);
    }
  }
}
