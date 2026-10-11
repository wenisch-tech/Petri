package tech.wenisch.petri.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.workspace.WorktreeLocator;

@Component
public class WorkspaceGitOperationCoordinator {
  private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
  private final WorktreeLocator worktrees;
  private final GitCommandRunner git;
  private final ProjectWorktreeRepository records;

  public WorkspaceGitOperationCoordinator(
      WorktreeLocator worktrees, GitCommandRunner git, ProjectWorktreeRepository records) {
    this.worktrees = worktrees;
    this.git = git;
    this.records = records;
  }

  public <T> T project(UUID projectId, Supplier<T> operation) {
    return locked("project:" + projectId, operation);
  }

  public <T> T worktree(UUID worktreeId, Supplier<T> operation) {
    UUID projectId = record(worktreeId).projectId();
    return locked("project:" + projectId,
        () -> locked("worktree:" + worktreeId, operation));
  }

  public void assertAgentAllowed(UUID worktreeId) {
    ReentrantLock projectLock = locks.get("project:" + record(worktreeId).projectId());
    ReentrantLock lock = locks.get("worktree:" + worktreeId);
    if (projectLock != null && projectLock.isLocked() || lock != null && lock.isLocked()) {
      throw new IllegalStateException("A Git operation is active for this worktree");
    }
    if (rebaseInProgress(worktreeId)) {
      throw new IllegalStateException("Resolve or abort the active Git rebase before running the agent");
    }
  }

  private ProjectWorktree record(UUID worktreeId) {
    return records.findById(worktreeId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown worktree"));
  }

  public boolean rebaseInProgress(UUID worktreeId) {
    Path root = worktrees.requireActiveWorktree(worktreeId);
    return gitPathExists(root, "rebase-merge") || gitPathExists(root, "rebase-apply");
  }

  private boolean gitPathExists(Path root, String name) {
    try {
      GitCommandResult result = git.run(root, List.of("rev-parse", "--git-path", name));
      if (!result.succeeded() || result.output().isBlank()) return false;
      Path path = Path.of(result.output().strip());
      if (!path.isAbsolute()) path = root.resolve(path).normalize();
      return Files.exists(path);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not inspect Git operation state", exception);
    }
  }

  private <T> T locked(String key, Supplier<T> operation) {
    ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock());
    if (!lock.tryLock()) throw new IllegalStateException("Git operation already in progress");
    try {
      return operation.get();
    } finally {
      lock.unlock();
      // Keep one stable lock per project/worktree. Removing an unlocked lock races with a
      // concurrent caller that has already obtained the same instance: the map could then
      // create a second lock and allow two Git operations for the same repository.
    }
  }
}
