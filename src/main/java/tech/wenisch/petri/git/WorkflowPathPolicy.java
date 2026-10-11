package tech.wenisch.petri.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.workspace.WorktreeLocator;

@Service
public class WorkflowPathPolicy {
  private final WorktreeLocator worktrees;
  private final GitCommandRunner git;
  public WorkflowPathPolicy(WorktreeLocator worktrees, GitCommandRunner git) {
    this.worktrees = worktrees; this.git = git;
  }
  public void assertNotIgnored(UUID worktreeId, String path) {
    try {
      Path root = worktrees.requireActiveWorktree(worktreeId);
      GitCommandResult result = git.run(root, List.of("check-ignore", "-q", "--", path));
      if (result.exitCode() == 0) {
        throw new IllegalArgumentException("Orchestrated workflows cannot modify ignored paths");
      }
      if (result.exitCode() != 1) throw new IllegalStateException("Could not validate workflow path policy");
    } catch (IOException exception) {
      throw new IllegalStateException("Could not validate workflow path policy", exception);
    }
  }
}
