package tech.wenisch.petri.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.workspace.WorkspacePaths;
import tech.wenisch.petri.workspace.WorktreeLocator;

@Component
class PersistedWorktreeLocator implements WorktreeLocator {
  private final ProjectWorktreeRepository worktrees;
  private final WorkspacePaths paths;

  PersistedWorktreeLocator(ProjectWorktreeRepository worktrees, WorkspacePaths paths) {
    this.worktrees = worktrees;
    this.paths = paths;
  }

  @Override
  @Transactional(readOnly = true)
  public Path requireActiveWorktree(UUID worktreeId) {
    ProjectWorktree worktree = worktrees.findById(worktreeId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown worktree"));
    if (worktree.status() != ProjectWorktreeStatus.ACTIVE) {
      throw new IllegalStateException("Worktree is not active");
    }
    try {
      Path expected = paths.runWorktree(worktree.projectId(), worktree.id());
      String expectedRelative = paths.root().relativize(expected).toString();
      if (!expectedRelative.equals(worktree.relativePath())) {
        throw new IllegalStateException("Recorded worktree path does not match its safe derived path");
      }
      if (Files.isSymbolicLink(expected)) {
        throw new IllegalStateException("Worktree root must not be a symbolic link");
      }
      if (!Files.isDirectory(expected, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException("Worktree directory is missing");
      }
      return paths.requireExistingPath(expected);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not resolve worktree", exception);
    }
  }
}
