package tech.wenisch.petri.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.workspace.WorktreeLocator;

/** Creates immutable Git trees without changing the worktree or its real index. */
@Service
public class WorkflowGitBaselineService {
  private final WorktreeLocator worktrees;
  private final GitCommandRunner git;

  public WorkflowGitBaselineService(WorktreeLocator worktrees, GitCommandRunner git) {
    this.worktrees = worktrees;
    this.git = git;
  }

  public Baseline create(UUID worktreeId, UUID workflowId) {
    Path root = worktrees.requireActiveWorktree(worktreeId);
    String head = require(root, List.of("rev-parse", "HEAD"), Map.of()).strip();
    Path index = null;
    try {
      index = Files.createTempFile("petri-workflow-index-", ".tmp");
      Files.deleteIfExists(index);
      Map<String, String> environment = Map.of("GIT_INDEX_FILE", index.toString());
      require(root, List.of("read-tree", "HEAD"), environment);
      require(root, List.of("add", "-A", "--", "."), environment);
      String tree = require(root, List.of("write-tree"), environment).strip();
      require(root, List.of("update-ref", ref(workflowId), tree), Map.of());
      return new Baseline(head, tree);
    } catch (IOException exception) {
      throw new GitOperationException("Could not create workflow baseline", exception);
    } finally {
      if (index != null) {
        try { Files.deleteIfExists(index); } catch (IOException ignored) { }
      }
    }
  }

  public String diff(UUID worktreeId, UUID workflowId, List<String> touchedPaths) {
    if (touchedPaths == null || touchedPaths.isEmpty()) return "";
    Path root = worktrees.requireActiveWorktree(worktreeId);
    Baseline current = snapshot(root);
    List<String> arguments = new ArrayList<>(List.of(
        "diff", "--no-ext-diff", "--binary", "--find-renames", ref(workflowId), current.tree(), "--"));
    for (String path : touchedPaths) arguments.add(safePath(path));
    return require(root, arguments, Map.of());
  }

  public void release(UUID worktreeId, UUID workflowId) {
    Path root = worktrees.requireActiveWorktree(worktreeId);
    try {
      git.run(root, List.of("update-ref", "-d", ref(workflowId)));
    } catch (IOException exception) {
      throw new GitOperationException("Could not release workflow baseline", exception);
    }
  }

  private Baseline snapshot(Path root) {
    Path index = null;
    try {
      index = Files.createTempFile("petri-workflow-current-", ".tmp");
      Files.deleteIfExists(index);
      Map<String, String> environment = Map.of("GIT_INDEX_FILE", index.toString());
      require(root, List.of("read-tree", "HEAD"), environment);
      require(root, List.of("add", "-A", "--", "."), environment);
      return new Baseline(null, require(root, List.of("write-tree"), environment).strip());
    } catch (IOException exception) {
      throw new GitOperationException("Could not snapshot workflow changes", exception);
    } finally {
      if (index != null) {
        try { Files.deleteIfExists(index); } catch (IOException ignored) { }
      }
    }
  }

  private String require(Path root, List<String> arguments, Map<String, String> environment) {
    try {
      GitCommandResult result = git.run(root, arguments, environment, List.of());
      if (!result.succeeded()) {
        throw new GitOperationException("Git failed while preparing workflow evidence"
            + (result.output().isBlank() ? "" : ": " + result.output().strip()));
      }
      return result.output();
    } catch (IOException exception) {
      throw new GitOperationException("Could not run Git for workflow evidence", exception);
    }
  }

  private static String ref(UUID workflowId) {
    return "refs/petri/workflows/" + workflowId + "/baseline";
  }

  private static String safePath(String value) {
    if (value == null || value.isBlank() || value.startsWith("/") || value.indexOf('\0') >= 0
        || value.contains("..") || value.equals(".git") || value.startsWith(".git/")) {
      throw new IllegalArgumentException("Workflow diff path is invalid");
    }
    return value;
  }

  public record Baseline(String head, String tree) {}
}
