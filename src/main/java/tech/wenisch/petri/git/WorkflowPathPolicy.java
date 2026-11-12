package tech.wenisch.petri.git;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.workspace.ActiveWorktree;
import tech.wenisch.petri.workspace.WorktreeLocator;

@Service
public class WorkflowPathPolicy {
  private final WorktreeLocator worktrees;
  public WorkflowPathPolicy(WorktreeLocator worktrees) {
    this.worktrees = worktrees;
  }
  public void assertNotIgnored(UUID worktreeId, String path) {
    try {
      ActiveWorktree active = worktrees.resolveActiveWorktree(worktreeId);
      GitCommandResult result = active.host().git().run(active.root(), List.of("check-ignore", "-q", "--", path));
      if (result.exitCode() == 0) {
        throw new IllegalArgumentException("Orchestrated workflows cannot modify ignored paths");
      }
      if (result.exitCode() != 1) throw new IllegalStateException("Could not validate workflow path policy");
    } catch (IOException exception) {
      throw new IllegalStateException("Could not validate workflow path policy", exception);
    }
  }
}
