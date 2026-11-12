package tech.wenisch.petri.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.workspace.ActiveWorktree;
import tech.wenisch.petri.workspace.WorkspaceHost;
import tech.wenisch.petri.workspace.WorktreeLocator;

/** Creates immutable Git trees without changing the worktree or its real index. */
@Service
public class WorkflowGitBaselineService {
  private final WorktreeLocator worktrees;

  public WorkflowGitBaselineService(WorktreeLocator worktrees) {
    this.worktrees = worktrees;
  }

  public Baseline create(UUID worktreeId, UUID workflowId) {
    ActiveWorktree active = worktrees.resolveActiveWorktree(worktreeId);
    WorkspaceHost host = active.host();
    Path root = active.root();
    String head = require(host, root, List.of("rev-parse", "HEAD"), Map.of()).strip();
    Path index = null;
    try {
      index = host.layout().temporaryDirectory().resolve("petri-workflow-index-" + UUID.randomUUID() + ".tmp");
      Map<String, String> environment = Map.of("GIT_INDEX_FILE", index.toString());
      require(host, root, List.of("read-tree", "HEAD"), environment);
      require(host, root, List.of("add", "-A", "--", "."), environment);
      String tree = require(host, root, List.of("write-tree"), environment).strip();
      require(host, root, List.of("update-ref", ref(workflowId), tree), Map.of());
      return new Baseline(head, tree);
    } catch (IOException exception) {
      throw new GitOperationException("Could not create workflow baseline", exception);
    } finally {
      if (index != null) host.layout().deleteTemporary(index);
    }
  }

  public String diff(UUID worktreeId, UUID workflowId, List<String> touchedPaths) {
    if (touchedPaths == null || touchedPaths.isEmpty()) return "";
    ActiveWorktree active = worktrees.resolveActiveWorktree(worktreeId);
    Path root = active.root();
    Baseline current = snapshot(active.host(), root);
    List<String> arguments = new ArrayList<>(List.of(
        "diff", "--no-ext-diff", "--binary", "--find-renames", ref(workflowId), current.tree(), "--"));
    for (String path : touchedPaths) arguments.add(safePath(path));
    return require(active.host(), root, arguments, Map.of());
  }

  public void release(UUID worktreeId, UUID workflowId) {
    ActiveWorktree active = worktrees.resolveActiveWorktree(worktreeId);
    try {
      active.host().git().run(active.root(), List.of("update-ref", "-d", ref(workflowId)));
    } catch (IOException exception) {
      throw new GitOperationException("Could not release workflow baseline", exception);
    }
  }

  private Baseline snapshot(WorkspaceHost host, Path root) {
    Path index = null;
    try {
      index = host.layout().temporaryDirectory().resolve("petri-workflow-current-" + UUID.randomUUID() + ".tmp");
      Map<String, String> environment = Map.of("GIT_INDEX_FILE", index.toString());
      require(host, root, List.of("read-tree", "HEAD"), environment);
      require(host, root, List.of("add", "-A", "--", "."), environment);
      return new Baseline(null, require(host, root, List.of("write-tree"), environment).strip());
    } catch (IOException exception) {
      throw new GitOperationException("Could not snapshot workflow changes", exception);
    } finally {
      if (index != null) host.layout().deleteTemporary(index);
    }
  }

  private String require(
      WorkspaceHost host, Path root, List<String> arguments, Map<String, String> environment) {
    try {
      GitCommandResult result = host.git().run(root, arguments, environment, List.of());
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
