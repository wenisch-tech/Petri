package tech.wenisch.petri.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.workspace.WorktreeLocator;

/** Read-only Git status and diff access for the active, safe Petri worktree. */
@Service
public class WorkspaceGitDiffService {
  private static final int MAX_DIFF_BYTES = 1_000_000;
  private final WorktreeLocator worktrees;
  private final GitCommandRunner git;

  public WorkspaceGitDiffService(WorktreeLocator worktrees, GitCommandRunner git) {
    this.worktrees = worktrees;
    this.git = git;
  }

  public List<WorkspaceChangeView> changes(UUID worktreeId) {
    Path root = worktrees.requireActiveWorktree(worktreeId);
    GitCommandResult result = run(root, List.of("status", "--porcelain=v1", "-z", "--untracked-files=all"));
    if (!result.succeeded()) throw new IllegalStateException("Git failed to inspect workspace changes");
    List<WorkspaceChangeView> changes = new ArrayList<>();
    String[] records = result.output().split("\\x00", -1);
    for (int index = 0; index < records.length; index++) {
      String record = records[index];
      if (record.isEmpty() || record.startsWith("!!")) continue;
      if (record.length() < 4) continue;
      String xy = record.substring(0, 2);
      String path = safePath(record.substring(3));
      boolean renameOrCopy = xy.indexOf('R') >= 0 || xy.indexOf('C') >= 0;
      String previousPath = null;
      if (renameOrCopy && ++index < records.length) previousPath = safePath(records[index]);
      WorkspaceChangeStatus status = status(xy);
      ChangeStats stats = stats(root, path, previousPath, status);
      changes.add(new WorkspaceChangeView(
          path, previousPath, status, xy.charAt(0) != ' ' && xy.charAt(0) != '?',
          xy.charAt(1) != ' ', stats.binary(), stats.additions(), stats.deletions()));
    }
    return changes.stream().sorted(Comparator.comparing(WorkspaceChangeView::path)).toList();
  }

  public WorkspaceDiffView diff(UUID worktreeId, String requestedPath) {
    return diff(worktreeId, requestedPath, WorkspaceDiffScope.COMBINED);
  }

  WorkspaceChangeView scopedChange(
      UUID worktreeId, WorkspaceChangeView change, WorkspaceDiffScope scope) {
    Path root = worktrees.requireActiveWorktree(worktreeId);
    ChangeStats stats = stats(
        root, change.path(), change.previousPath(), change.status(), scope);
    return new WorkspaceChangeView(
        change.path(), change.previousPath(), change.status(), change.staged(), change.unstaged(),
        stats.binary(), stats.additions(), stats.deletions());
  }

  public WorkspaceDiffView diff(
      UUID worktreeId, String requestedPath, WorkspaceDiffScope requestedScope) {
    Path root = worktrees.requireActiveWorktree(worktreeId);
    String path = safePath(requestedPath);
    WorkspaceDiffScope scope = requestedScope == null ? WorkspaceDiffScope.COMBINED : requestedScope;
    WorkspaceChangeView change = changes(worktreeId).stream()
        .filter(value -> value.path().equals(path) || path.equals(value.previousPath()))
        .findFirst().orElseThrow(() -> new IllegalArgumentException("File has no Git changes"));
    String beforePath = change.previousPath() == null ? change.path() : change.previousPath();
    Content before;
    Content after;
    List<String> patchArguments = new ArrayList<>(List.of(
        "diff", "--no-ext-diff", "--no-textconv", "--no-color", "--find-renames"));
    if (scope == WorkspaceDiffScope.STAGED) {
      before = change.status() == WorkspaceChangeStatus.ADDED ? Content.empty() : fromHead(root, beforePath);
      after = fromIndex(root, change.path());
      patchArguments.add("--cached");
    } else if (scope == WorkspaceDiffScope.UNSTAGED) {
      before = change.status() == WorkspaceChangeStatus.UNTRACKED
          ? Content.empty() : fromIndex(root, beforePath);
      after = change.status() == WorkspaceChangeStatus.DELETED
          ? Content.empty() : fromWorktree(root, change.path());
    } else {
      before = change.status() == WorkspaceChangeStatus.UNTRACKED
          || change.status() == WorkspaceChangeStatus.ADDED ? Content.empty() : fromHead(root, beforePath);
      after = change.status() == WorkspaceChangeStatus.DELETED
          ? Content.empty() : fromWorktree(root, change.path());
      patchArguments.add("HEAD");
    }
    patchArguments.add("--");
    patchArguments.add(path);
    GitCommandResult patch = run(root, patchArguments);
    String unified = patch.succeeded() || patch.exitCode() == 1 ? patch.output() : "";
    boolean binary = before.binary() || after.binary() || change.binary();
    return new WorkspaceDiffView(change.path(), change.previousPath(), change.status(), binary,
        before.truncated() || after.truncated() || patch.truncated(),
        binary ? null : before.text(), binary ? null : after.text(), unified);
  }

  private Content fromIndex(Path root, String path) {
    GitCommandResult result = run(root, List.of("show", ":" + path));
    if (!result.succeeded()) return Content.empty();
    return content(result.output(), result.truncated());
  }

  private ChangeStats stats(Path root, String path, String previousPath, WorkspaceChangeStatus status) {
    return stats(root, path, previousPath, status, WorkspaceDiffScope.COMBINED);
  }

  private ChangeStats stats(
      Path root,
      String path,
      String previousPath,
      WorkspaceChangeStatus status,
      WorkspaceDiffScope scope) {
    if (status == WorkspaceChangeStatus.UNTRACKED) {
      Content value = fromWorktree(root, path);
      return value.binary() ? ChangeStats.binaryStats() : new ChangeStats(lines(value.text()), 0, false);
    }
    List<String> arguments = new ArrayList<>(List.of(
        "diff", "--numstat", "--no-ext-diff", "--no-textconv"));
    if (scope == WorkspaceDiffScope.STAGED) arguments.addAll(List.of("--cached", "HEAD"));
    else if (scope == WorkspaceDiffScope.COMBINED) arguments.add("HEAD");
    arguments.add("--");
    if (previousPath != null) arguments.add(previousPath);
    arguments.add(path);
    GitCommandResult result = run(root, arguments);
    if (!result.succeeded() && result.exitCode() != 1) return new ChangeStats(null, null, false);
    String first = result.output().lines().findFirst().orElse("");
    String[] values = first.split("\\t", 3);
    if (values.length < 2) return new ChangeStats(null, null, false);
    if ("-".equals(values[0]) || "-".equals(values[1])) return ChangeStats.binaryStats();
    try {
      return new ChangeStats(Integer.parseInt(values[0]), Integer.parseInt(values[1]), false);
    } catch (NumberFormatException ignored) {
      return new ChangeStats(null, null, false);
    }
  }

  private Content fromHead(Path root, String path) {
    GitCommandResult result = run(root, List.of("show", "HEAD:" + path));
    if (!result.succeeded()) return Content.empty();
    return content(result.output(), result.truncated());
  }

  private Content fromWorktree(Path root, String path) {
    try {
      Path file = root.resolve(path).normalize();
      if (!file.startsWith(root) || Files.isSymbolicLink(file)
          || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Content.empty();
      long size = Files.size(file);
      if (size > MAX_DIFF_BYTES) return new Content(null, false, true);
      byte[] bytes = Files.readAllBytes(file);
      if (bytes.length > MAX_DIFF_BYTES) return new Content(null, false, true);
      String text = new String(bytes, StandardCharsets.UTF_8);
      return new Content(text, text.indexOf('\0') >= 0, false);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not read changed workspace file", exception);
    }
  }

  private static Content content(String value, boolean truncated) {
    return new Content(value, value.indexOf('\0') >= 0, truncated);
  }

  private GitCommandResult run(Path root, List<String> arguments) {
    try {
      return git.run(root, arguments);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not run Git", exception);
    }
  }

  private static WorkspaceChangeStatus status(String xy) {
    if ("??".equals(xy)) return WorkspaceChangeStatus.UNTRACKED;
    if (xy.indexOf('U') >= 0 || "AA".equals(xy) || "DD".equals(xy)) {
      return WorkspaceChangeStatus.CONFLICT;
    }
    if (xy.indexOf('R') >= 0) return WorkspaceChangeStatus.RENAMED;
    if (xy.indexOf('C') >= 0) return WorkspaceChangeStatus.COPIED;
    if (xy.indexOf('D') >= 0) return WorkspaceChangeStatus.DELETED;
    if (xy.indexOf('A') >= 0) return WorkspaceChangeStatus.ADDED;
    return WorkspaceChangeStatus.MODIFIED;
  }

  private static String safePath(String value) {
    if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Workspace path is invalid");
    }
    Path path = Path.of(value).normalize();
    boolean gitMetadata = java.util.stream.StreamSupport.stream(path.spliterator(), false)
        .anyMatch(segment -> ".git".equals(segment.toString()));
    if (path.isAbsolute() || path.startsWith("..") || gitMetadata) {
      throw new IllegalArgumentException("Workspace path is invalid");
    }
    return path.toString().replace(path.getFileSystem().getSeparator(), "/");
  }

  private static int lines(String value) {
    return value == null || value.isEmpty() ? 0 : (int) value.lines().count();
  }

  private record ChangeStats(Integer additions, Integer deletions, boolean binary) {
    static ChangeStats binaryStats() { return new ChangeStats(null, null, true); }
  }

  private record Content(String text, boolean binary, boolean truncated) {
    static Content empty() { return new Content("", false, false); }
  }
}
