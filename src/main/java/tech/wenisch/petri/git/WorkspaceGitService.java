package tech.wenisch.petri.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.agent.orchestration.AgentRunService;
import tech.wenisch.petri.agent.run.TestRunService;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;
import tech.wenisch.petri.workspace.WorkspacePaths;
import tech.wenisch.petri.workspace.WorktreeLocator;
import tech.wenisch.petri.git.provider.ProjectProviderSourceRepository;
import tech.wenisch.petri.git.provider.ProjectCredentialMode;

@Service
public class WorkspaceGitService {
  private final ProjectRepository projects;
  private final ProjectWorktreeRepository worktreeRecords;
  private final ProjectWorktreeService worktrees;
  private final ProjectMirrorService mirrors;
  private final WorktreeLocator locator;
  private final WorkspacePaths paths;
  private final GitCommandRunner git;
  private final WorkspaceGitDiffService diffs;
  private final WorkspaceGitOperationCoordinator operations;
  private final AgentRunService agentRuns;
  private final TestRunService testRuns;
  private final AppUserRepository users;
  private final AuditLogService audit;
  private final ProjectProviderSourceRepository providerSources;

  public WorkspaceGitService(
      ProjectRepository projects,
      ProjectWorktreeRepository worktreeRecords,
      ProjectWorktreeService worktrees,
      ProjectMirrorService mirrors,
      WorktreeLocator locator,
      WorkspacePaths paths,
      GitCommandRunner git,
      WorkspaceGitDiffService diffs,
      WorkspaceGitOperationCoordinator operations,
      AgentRunService agentRuns,
      TestRunService testRuns,
      AppUserRepository users,
      AuditLogService audit,
      ProjectProviderSourceRepository providerSources) {
    this.projects = projects;
    this.worktreeRecords = worktreeRecords;
    this.worktrees = worktrees;
    this.mirrors = mirrors;
    this.locator = locator;
    this.paths = paths;
    this.git = git;
    this.diffs = diffs;
    this.operations = operations;
    this.agentRuns = agentRuns;
    this.testRuns = testRuns;
    this.users = users;
    this.audit = audit;
    this.providerSources = providerSources;
  }

  @Transactional(readOnly = true)
  public List<GitBranchView> branches(UUID projectId) {
    Project project = project(projectId);
    Path mirror = mirror(projectId);
    if (!mirrors.status(projectId).initialized()) return List.of();
    Map<String, ProjectWorktree> bound = new HashMap<>();
    for (ProjectWorktree worktree : worktreeRecords.findByProjectIdAndStatusOrderByCreatedAtDesc(
        projectId, ProjectWorktreeStatus.ACTIVE)) {
      if (worktree.branchName() != null) bound.put(worktree.branchName(), worktree);
    }
    List<GitBranchView> values = new ArrayList<>();
    for (String branch : refs(mirror, "refs/heads")) {
      String upstream = upstream(mirror, branch);
      int[] distance = upstream == null ? new int[] {0, 0} : distance(mirror, branch, upstream);
      ProjectWorktree worktree = bound.get(branch);
      values.add(new GitBranchView(
          branch, GitBranchKind.LOCAL, branch.equals(project.getDefaultBranch()), upstream,
          distance[0], distance[1], worktree == null ? null : worktree.id(),
          branch.equals(project.getDefaultBranch()) || worktree != null));
    }
    for (String remote : refs(mirror, "refs/remotes/origin")) {
      if ("HEAD".equals(remote)) continue;
      ProjectWorktree worktree = bound.get(remote);
      values.add(new GitBranchView(
          remote, GitBranchKind.REMOTE, remote.equals(project.getDefaultBranch()), null,
          null, null, worktree == null ? null : worktree.id(),
          remote.equals(project.getDefaultBranch()) || worktree != null));
    }
    return values.stream().sorted(Comparator
        .comparing(GitBranchView::kind)
        .thenComparing(GitBranchView::name)).toList();
  }

  public List<GitBranchView> fetch(UUID projectId, String actorEmail) {
    return operations.project(projectId, () -> {
      mirrors.synchronize(projectId, actorEmail);
      return branches(projectId);
    });
  }

  public ProjectWorktreeView createBranch(
      UUID projectId, String requestedName, String requestedStartPoint, String actorEmail) {
    return operations.project(projectId, () -> {
      Project project = project(projectId);
      Path mirror = mirror(projectId);
      requireInitialized(projectId);
      String name = validBranch(mirror, requestedName);
      if (refExists(mirror, "refs/heads/" + name)) {
        throw new IllegalStateException("Local branch already exists");
      }
      String start = normalizeStartPoint(mirror, requestedStartPoint, project.getDefaultBranch());
      requireSuccess(run(mirror, List.of(
          "--git-dir=" + mirror, "branch", "--no-track", "--", name, start)),
          "create branch");
      audit(actorEmail, "GIT_BRANCH_CREATED", projectId,
          "Created branch " + name + " from " + start);
      return worktrees.createBranchWorktree(
          projectId, name, start, null, actorEmail);
    });
  }

  public ProjectWorktreeView openBranch(
      UUID projectId, String requestedName, GitBranchKind kind, String actorEmail) {
    return operations.project(projectId, () -> {
      project(projectId);
      Path mirror = mirror(projectId);
      requireInitialized(projectId);
      String name = validBranch(mirror, requestedName);
      ProjectWorktree existing = activeBranchWorktree(projectId, name);
      if (existing != null) return view(existing);
      String localRef = "refs/heads/" + name;
      String upstream = upstream(mirror, name);
      if (!refExists(mirror, localRef)) {
        if (kind != GitBranchKind.REMOTE || !refExists(mirror, "refs/remotes/origin/" + name)) {
          throw new IllegalArgumentException("Unknown branch");
        }
        requireSuccess(run(mirror, List.of(
            "--git-dir=" + mirror, "branch", "--", name, "refs/remotes/origin/" + name)),
            "create tracking branch");
        configureUpstream(mirror, name, name);
        upstream = "origin/" + name;
        audit(actorEmail, "GIT_BRANCH_CREATED", projectId,
            "Created tracking branch " + name);
      }
      return worktrees.createBranchWorktree(
          projectId, name, localRef, upstream, actorEmail);
    });
  }

  public List<GitBranchView> deleteBranch(
      UUID projectId,
      String requestedName,
      GitBranchKind kind,
      boolean force,
      String confirmation,
      String actorEmail) {
    return operations.project(projectId, () -> {
      Project project = project(projectId);
      Path mirror = mirror(projectId);
      String name = validBranch(mirror, requestedName);
      if (!name.equals(confirmation)) {
        throw new IllegalArgumentException("Type the exact branch name to confirm deletion");
      }
      if (name.equals(project.getDefaultBranch())) {
        throw new IllegalStateException("The default branch is protected");
      }
      if (activeBranchWorktree(projectId, name) != null) {
        throw new IllegalStateException("Remove the active branch worktree before deleting the branch");
      }
      if (kind == GitBranchKind.LOCAL) {
        requireSuccess(run(mirror, List.of(
            "--git-dir=" + mirror, "branch", force ? "-D" : "-d", "--", name)),
            "delete local branch");
        audit(actorEmail, "GIT_BRANCH_DELETED", projectId, "Deleted local branch " + name);
      } else {
        requireRemoteWrite(projectId);
        requireSuccess(remote(projectId, mirror, List.of(
            "--git-dir=" + mirror, "-c", "remote.origin.mirror=false",
            "push", "origin", "--delete", name)),
            "delete remote branch");
        run(mirror, List.of(
            "--git-dir=" + mirror, "update-ref", "-d", "refs/remotes/origin/" + name));
        audit(actorEmail, "GIT_REMOTE_BRANCH_DELETED", projectId,
            "Deleted remote branch " + name);
      }
      return branches(projectId);
    });
  }

  @Transactional(readOnly = true)
  public WorkspaceGitStatusView status(UUID worktreeId) {
    ProjectWorktree record = record(worktreeId);
    Path root = locator.requireActiveWorktree(worktreeId);
    String head = output(root, List.of("rev-parse", "HEAD"));
    String upstream = record.upstreamBranch();
    if (upstream == null && record.branchName() != null) upstream = upstream(root, record.branchName());
    BranchDistance distance = BranchDistance.of(git, root, upstream);
    List<WorkspaceChangeView> all = diffs.changes(worktreeId);
    List<String> conflicts = all.stream()
        .filter(value -> value.status() == WorkspaceChangeStatus.CONFLICT)
        .map(WorkspaceChangeView::path).toList();
    List<WorkspaceChangeView> staged = all.stream()
        .filter(WorkspaceChangeView::staged)
        .filter(value -> value.status() != WorkspaceChangeStatus.UNTRACKED)
        .map(value -> diffs.scopedChange(worktreeId, value, WorkspaceDiffScope.STAGED)).toList();
    List<WorkspaceChangeView> untracked = all.stream()
        .filter(value -> value.status() == WorkspaceChangeStatus.UNTRACKED)
        .map(value -> diffs.scopedChange(worktreeId, value, WorkspaceDiffScope.UNSTAGED)).toList();
    List<WorkspaceChangeView> unstaged = all.stream()
        .filter(WorkspaceChangeView::unstaged)
        .filter(value -> value.status() != WorkspaceChangeStatus.UNTRACKED)
        .map(value -> diffs.scopedChange(worktreeId, value, WorkspaceDiffScope.UNSTAGED)).toList();
    boolean rebase = operations.rebaseInProgress(worktreeId);
    return new WorkspaceGitStatusView(
        worktreeId, record.branchName(), upstream, head, record.detached(),
        distance.ahead(), distance.behind(), all.isEmpty(),
        rebase ? WorkspaceGitOperationState.REBASE_IN_PROGRESS : WorkspaceGitOperationState.IDLE,
        conflicts, staged, unstaged, untracked);
  }

  public WorkspaceGitStatusView stage(UUID worktreeId, List<String> requestedPaths, String actorEmail) {
    return operations.worktree(worktreeId, () -> {
      requireNoActiveExecution(worktreeId);
      Path root = locator.requireActiveWorktree(worktreeId);
      List<String> paths = validPaths(requestedPaths);
      List<String> arguments = new ArrayList<>(List.of("add", "--"));
      arguments.addAll(paths);
      requireSuccess(run(root, arguments), "stage files");
      audit(actorEmail, "GIT_CHANGES_STAGED", worktreeId, "Staged " + paths.size() + " paths");
      return status(worktreeId);
    });
  }

  public WorkspaceGitStatusView unstage(UUID worktreeId, List<String> requestedPaths, String actorEmail) {
    return operations.worktree(worktreeId, () -> {
      requireNoActiveExecution(worktreeId);
      Path root = locator.requireActiveWorktree(worktreeId);
      List<String> paths = validPaths(requestedPaths);
      List<String> arguments = new ArrayList<>(List.of("reset", "-q", "HEAD", "--"));
      arguments.addAll(paths);
      requireSuccess(run(root, arguments), "unstage files");
      audit(actorEmail, "GIT_CHANGES_UNSTAGED", worktreeId, "Unstaged " + paths.size() + " paths");
      return status(worktreeId);
    });
  }

  public WorkspaceGitStatusView discard(UUID worktreeId, List<String> requestedPaths, String actorEmail) {
    return operations.worktree(worktreeId, () -> {
      requireNoActiveExecution(worktreeId);
      Path root = locator.requireActiveWorktree(worktreeId);
      List<String> paths = validPaths(requestedPaths);
      Map<String, WorkspaceChangeView> changes = new HashMap<>();
      for (WorkspaceChangeView change : diffs.changes(worktreeId)) changes.put(change.path(), change);
      for (String path : paths) {
        WorkspaceChangeView change = changes.get(path);
        if (change == null) continue;
        if (change.status() == WorkspaceChangeStatus.UNTRACKED) {
          requireSuccess(run(root, List.of("clean", "-f", "-q", "--", path)), "discard untracked file");
        } else if (change.status() == WorkspaceChangeStatus.ADDED
            || change.status() == WorkspaceChangeStatus.COPIED) {
          requireSuccess(run(root, List.of("rm", "-f", "-q", "--", path)), "discard added file");
        } else if (change.status() == WorkspaceChangeStatus.RENAMED && change.previousPath() != null) {
          requireSuccess(run(root, List.of("rm", "-f", "-q", "--", path)), "discard renamed file");
          requireSuccess(run(root, List.of("checkout", "HEAD", "--", change.previousPath())),
              "restore renamed file");
        } else {
          requireSuccess(run(root, List.of("checkout", "HEAD", "--", path)), "discard changes");
        }
      }
      audit(actorEmail, "GIT_CHANGES_DISCARDED", worktreeId, "Discarded " + paths.size() + " paths");
      return status(worktreeId);
    });
  }

  /**
   * What a commit would contain, for message generation: changed files with their status, a bounded
   * diff and recent commit subjects. With {@code includeUnstaged} it covers everything that
   * "stage all" would add.
   */
  public record CommitContext(
      List<WorkspaceChangeView> files, String diff, boolean truncated, List<String> recentSubjects) {}

  public CommitContext commitContext(UUID worktreeId, boolean includeUnstaged, int maxChars) {
    return operations.worktree(worktreeId, () -> {
      Path root = locator.requireActiveWorktree(worktreeId);
      WorkspaceGitStatusView status = status(worktreeId);
      List<WorkspaceChangeView> files = new ArrayList<>(status.staged());
      if (includeUnstaged) {
        for (WorkspaceChangeView change : status.unstaged()) {
          if (files.stream().noneMatch(value -> value.path().equals(change.path()))) files.add(change);
        }
        for (WorkspaceChangeView change : status.untracked()) {
          if (files.stream().noneMatch(value -> value.path().equals(change.path()))) files.add(change);
        }
      }
      StringBuilder diff = new StringBuilder();
      List<String> base = List.of("--no-pager", "diff", "--no-color", "--no-ext-diff", "--no-textconv",
          "--unified=2");
      if (includeUnstaged) {
        GitCommandResult head = run(root, withTail(base, "HEAD", "--"));
        if (head.succeeded()) diff.append(head.output());
        else {
          diff.append(run(root, withTail(base, "--cached", "--")).output());
          diff.append(run(root, withTail(base, "--")).output());
        }
      } else {
        diff.append(run(root, withTail(base, "--cached", "--")).output());
      }
      if (includeUnstaged) {
        int shown = 0;
        for (WorkspaceChangeView change : status.untracked()) {
          if (shown++ >= 10 || change.binary()) continue;
          Path file = root.resolve(change.path()).normalize();
          try {
            if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > 200_000) continue;
            String text = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
            if (text.indexOf('\0') >= 0) continue;
            diff.append("\nnew file: ").append(change.path()).append('\n')
                .append(text.length() > 3_000 ? text.substring(0, 3_000) + "\n[...]" : text).append('\n');
          } catch (IOException ignored) {
            // an unreadable new file just has no preview
          }
        }
      }
      boolean truncated = diff.length() > maxChars;
      String bounded = truncated ? diff.substring(0, maxChars) : diff.toString();
      List<String> subjects = new ArrayList<>();
      GitCommandResult log = run(root, List.of("log", "-8", "--format=%s"));
      if (log.succeeded()) {
        for (String line : log.output().split("\\R")) if (!line.isBlank()) subjects.add(line.strip());
      }
      return new CommitContext(List.copyOf(files), bounded, truncated, List.copyOf(subjects));
    });
  }

  private static List<String> withTail(List<String> base, String... tail) {
    List<String> value = new ArrayList<>(base);
    value.addAll(List.of(tail));
    return value;
  }

  public WorkspaceGitStatusView commit(
      UUID worktreeId, String requestedMessage, boolean stageAll, String actorEmail) {
    return operations.worktree(worktreeId, () -> {
      requireNoActiveExecution(worktreeId);
      Path root = locator.requireActiveWorktree(worktreeId);
      String message = required(requestedMessage, "Commit message", 10_000);
      if (stageAll) requireSuccess(run(root, List.of("add", "-A")), "stage all changes");
      if (status(worktreeId).staged().isEmpty()) {
        throw new IllegalStateException("There are no staged changes to commit");
      }
      String email = actor(actorEmail).getEmail();
      int separator = email.indexOf('@');
      String name = separator > 0 ? email.substring(0, separator) : email;
      requireSuccess(run(root, List.of(
          "-c", "user.name=" + name, "-c", "user.email=" + email,
          "commit", "-m", message)), "commit changes");
      audit(actorEmail, "GIT_COMMITTED", worktreeId, "Created Git commit");
      return status(worktreeId);
    });
  }

  public WorkspaceGitStatusView pull(UUID worktreeId, String actorEmail) {
    ProjectWorktree record = record(worktreeId);
    return operations.worktree(worktreeId, () -> {
      requireBranch(record);
      requireNoActiveExecution(worktreeId);
      requireClean(worktreeId);
      mirrors.synchronize(record.projectId(), actorEmail);
      Path root = locator.requireActiveWorktree(worktreeId);
      String upstream = record.upstreamBranch() == null
          ? "origin/" + record.branchName() : record.upstreamBranch();
      requireSuccess(run(root, List.of("merge", "--ff-only", upstream)), "fast-forward branch");
      audit(actorEmail, "GIT_PULLED", worktreeId, "Fast-forwarded from " + upstream);
      return status(worktreeId);
    });
  }

  public WorkspaceGitStatusView rebase(UUID worktreeId, String requestedTarget, String actorEmail) {
    ProjectWorktree record = record(worktreeId);
    return operations.worktree(worktreeId, () -> {
      requireBranch(record);
      requireNoActiveExecution(worktreeId);
      requireClean(worktreeId);
      Path root = locator.requireActiveWorktree(worktreeId);
      String target = requestedTarget == null || requestedTarget.isBlank()
          ? record.upstreamBranch() : requestedTarget.strip();
      if (target == null) throw new IllegalStateException("Branch has no upstream to rebase onto");
      target = normalizeRebaseTarget(root, record.projectId(), target);
      GitCommandResult result = run(root, List.of("rebase", target));
      if (!result.succeeded() && !operations.rebaseInProgress(worktreeId)) {
        requireSuccess(result, "rebase branch");
      }
      audit(actorEmail, "GIT_REBASE_STARTED", worktreeId, "Rebased onto " + target);
      return status(worktreeId);
    });
  }

  public WorkspaceGitStatusView continueRebase(UUID worktreeId, String actorEmail) {
    return operations.worktree(worktreeId, () -> {
      requireNoActiveExecution(worktreeId);
      if (!operations.rebaseInProgress(worktreeId)) {
        throw new IllegalStateException("No rebase is in progress");
      }
      WorkspaceGitStatusView before = status(worktreeId);
      if (!before.conflicts().isEmpty()) {
        throw new IllegalStateException("Resolve and stage every conflict before continuing");
      }
      Path root = locator.requireActiveWorktree(worktreeId);
      String email = actor(actorEmail).getEmail();
      int separator = email.indexOf('@');
      String name = separator > 0 ? email.substring(0, separator) : email;
      GitCommandResult result;
      try {
        result = git.run(root, List.of(
                "-c", "user.name=" + name, "-c", "user.email=" + email,
                "rebase", "--continue"),
            Map.of("GIT_EDITOR", "true"), List.of());
      } catch (IOException exception) {
        throw new GitOperationException("Could not continue rebase", exception);
      }
      requireSuccess(result, "continue rebase");
      audit(actorEmail, "GIT_REBASE_CONTINUED", worktreeId, "Continued Git rebase");
      return status(worktreeId);
    });
  }

  public WorkspaceGitStatusView abortRebase(UUID worktreeId, String actorEmail) {
    return operations.worktree(worktreeId, () -> {
      requireNoActiveExecution(worktreeId);
      if (!operations.rebaseInProgress(worktreeId)) {
        throw new IllegalStateException("No rebase is in progress");
      }
      Path root = locator.requireActiveWorktree(worktreeId);
      requireSuccess(run(root, List.of("rebase", "--abort")), "abort rebase");
      audit(actorEmail, "GIT_REBASE_ABORTED", worktreeId, "Aborted Git rebase");
      return status(worktreeId);
    });
  }

  @Transactional
  public WorkspaceGitStatusView push(UUID worktreeId, boolean forceWithLease, String actorEmail) {
    ProjectWorktree record = record(worktreeId);
    return operations.worktree(worktreeId, () -> {
      requireRemoteWrite(record.projectId());
      requireBranch(record);
      requireNoActiveExecution(worktreeId);
      requireClean(worktreeId);
      Path root = locator.requireActiveWorktree(worktreeId);
      // Mirrors are cloned with remote.origin.mirror=true, which git refuses to combine with refspecs.
      List<String> arguments = new ArrayList<>(List.of("-c", "remote.origin.mirror=false", "push"));
      if (forceWithLease) arguments.add("--force-with-lease");
      if (record.upstreamBranch() == null) arguments.addAll(List.of("-u", "origin", record.branchName()));
      else arguments.addAll(List.of("origin", record.branchName()));
      GitCommandResult pushed = remote(record.projectId(), root, arguments);
      if (!pushed.succeeded() && pushed.output().toLowerCase(java.util.Locale.ROOT).contains("authentication failed")) {
        throw new GitOperationException("Push was rejected: the stored Git credential could not "
            + "authenticate for writing. Check that its token has write access to this repository "
            + "(for example the write:repository scope) and has not expired, then replace the "
            + "credential in the project settings or Git account.");
      }
      requireSuccess(pushed, "push branch");
      if (record.upstreamBranch() == null) {
        record.upstreamBranch("origin/" + record.branchName());
        worktreeRecords.save(record);
      }
      audit(actorEmail, forceWithLease ? "GIT_FORCE_WITH_LEASE_PUSHED" : "GIT_PUSHED",
          worktreeId, "Pushed branch " + record.branchName());
      return status(worktreeId);
    });
  }

  private void requireNoActiveExecution(UUID worktreeId) {
    if (agentRuns.hasActiveForWorktree(worktreeId) || testRuns.hasActiveForWorktree(worktreeId)) {
      throw new IllegalStateException("Git actions are blocked while agent or test work is pending");
    }
  }

  private void requireRemoteWrite(UUID projectId) {
    providerSources.findByProjectId(projectId).ifPresent(source -> {
      if (source.getCredentialMode() == ProjectCredentialMode.CONNECTION && !source.isCanWrite()) {
        throw new IllegalStateException("Connected repository is read-only; push and remote branch changes are disabled");
      }
    });
  }

  private void requireClean(UUID worktreeId) {
    WorkspaceGitStatusView value = status(worktreeId);
    if (!value.clean()) throw new IllegalStateException("Git action requires a clean worktree");
    if (value.operation() != WorkspaceGitOperationState.IDLE) {
      throw new IllegalStateException("Resolve or abort the active rebase first");
    }
  }

  private static void requireBranch(ProjectWorktree record) {
    if (record.detached()) throw new IllegalStateException("Git action requires a branch-bound worktree");
  }

  private List<String> validPaths(List<String> requested) {
    if (requested == null || requested.isEmpty()) throw new IllegalArgumentException("At least one path is required");
    LinkedHashSet<String> values = new LinkedHashSet<>();
    for (String value : requested) {
      if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("Workspace path is invalid");
      }
      Path normalized = Path.of(value).normalize();
      boolean gitMetadata = false;
      for (Path part : normalized) {
        if (".git".equals(part.toString())) {
          gitMetadata = true;
          break;
        }
      }
      String normalizedValue = normalized.toString();
      if (normalizedValue.isBlank() || ".".equals(normalizedValue)
          || normalized.isAbsolute() || normalized.startsWith("..") || gitMetadata) {
        throw new IllegalArgumentException("Workspace path is invalid");
      }
      values.add(normalizedValue.replace(normalized.getFileSystem().getSeparator(), "/"));
    }
    return List.copyOf(values);
  }

  private String validBranch(Path mirror, String requested) {
    String name = required(requested, "Branch name", 255);
    GitCommandResult result = run(mirror, List.of(
        "--git-dir=" + mirror, "check-ref-format", "--branch", name));
    if (!result.succeeded() || name.startsWith("-") || name.startsWith("origin/")) {
      throw new IllegalArgumentException("Branch name is invalid");
    }
    return name;
  }

  private String normalizeStartPoint(Path mirror, String requested, String fallback) {
    String value = requested == null || requested.isBlank() ? fallback : requested.strip();
    if (value.startsWith("origin/")) {
      String remote = value.substring("origin/".length());
      if (refExists(mirror, "refs/remotes/origin/" + remote)) return "refs/remotes/origin/" + remote;
    }
    if (refExists(mirror, "refs/heads/" + value)) return "refs/heads/" + value;
    if (refExists(mirror, "refs/remotes/origin/" + value)) return "refs/remotes/origin/" + value;
    throw new IllegalArgumentException("Unknown branch start point");
  }

  private String normalizeRebaseTarget(Path root, UUID projectId, String requested) {
    String value = required(requested, "Branch name", 255);
    if (value.startsWith("origin/")) {
      String remote = value.substring("origin/".length());
      validBranch(mirror(projectId), remote);
      if (run(root, List.of("show-ref", "--verify", "--quiet",
          "refs/remotes/origin/" + remote)).succeeded()) return value;
    } else {
      validBranch(mirror(projectId), value);
      if (run(root, List.of("show-ref", "--verify", "--quiet", "refs/heads/" + value)).succeeded()) {
        return value;
      }
    }
    throw new IllegalArgumentException("Unknown rebase target");
  }

  private void configureUpstream(Path mirror, String branch, String upstream) {
    requireSuccess(run(mirror, List.of(
        "--git-dir=" + mirror, "config", "branch." + branch + ".remote", "origin")),
        "configure branch remote");
    requireSuccess(run(mirror, List.of(
        "--git-dir=" + mirror, "config", "branch." + branch + ".merge",
        "refs/heads/" + upstream)), "configure branch upstream");
  }

  private String upstream(Path root, String branch) {
    GitCommandResult result = run(root, List.of(
        "rev-parse", "--abbrev-ref", "--symbolic-full-name", branch + "@{upstream}"));
    return result.succeeded() && !result.output().isBlank() ? result.output().strip() : null;
  }

  private int[] distance(Path root, String local, String upstream) {
    GitCommandResult result = run(root, List.of(
        "rev-list", "--left-right", "--count", local + "..." + upstream));
    if (!result.succeeded()) return new int[] {0, 0};
    String[] values = result.output().strip().split("\\s+");
    if (values.length != 2) return new int[] {0, 0};
    try {
      return new int[] {Integer.parseInt(values[0]), Integer.parseInt(values[1])};
    } catch (NumberFormatException ignored) {
      return new int[] {0, 0};
    }
  }

  private List<String> refs(Path mirror, String namespace) {
    GitCommandResult result = run(mirror, List.of(
        "--git-dir=" + mirror, "for-each-ref", "--format=%(refname)", namespace));
    requireSuccess(result, "list branches");
    String prefix = namespace + "/";
    return result.output().lines().map(String::strip)
        .filter(value -> value.startsWith(prefix))
        .map(value -> value.substring(prefix.length())).toList();
  }

  private boolean refExists(Path mirror, String reference) {
    return run(mirror, List.of(
        "--git-dir=" + mirror, "show-ref", "--verify", "--quiet", reference)).succeeded();
  }

  private ProjectWorktree activeBranchWorktree(UUID projectId, String branch) {
    return worktreeRecords.findByProjectIdAndStatusOrderByCreatedAtDesc(
            projectId, ProjectWorktreeStatus.ACTIVE).stream()
        .filter(value -> branch.equals(value.branchName())).findFirst().orElse(null);
  }

  private ProjectWorktree record(UUID worktreeId) {
    return worktreeRecords.findById(worktreeId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown worktree"));
  }

  private Project project(UUID projectId) {
    return projects.findById(projectId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown project"));
  }

  private AppUser actor(String email) {
    return users.findByEmailIgnoreCase(email)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  private void requireInitialized(UUID projectId) {
    if (!mirrors.status(projectId).initialized()) {
      throw new IllegalStateException("Synchronize the project mirror first");
    }
  }

  private Path mirror(UUID projectId) {
    try {
      return paths.mirror(projectId);
    } catch (IOException exception) {
      throw new GitOperationException("Could not resolve project mirror", exception);
    }
  }

  private GitCommandResult run(Path root, List<String> arguments) {
    try {
      return git.run(root, arguments);
    } catch (IOException exception) {
      throw new GitOperationException("Could not run Git", exception);
    }
  }

  private GitCommandResult remote(UUID projectId, Path root, List<String> arguments) {
    try {
      return mirrors.runRemote(projectId, root, arguments);
    } catch (IOException exception) {
      throw new GitOperationException("Could not run authenticated Git operation", exception);
    }
  }

  private String output(Path root, List<String> arguments) {
    GitCommandResult result = run(root, arguments);
    requireSuccess(result, "inspect repository");
    return result.output().strip();
  }

  private void audit(String actor, String action, UUID target, String summary) {
    audit.record(actor(actor).getEmail(), action, "GIT_WORKSPACE", target, summary);
  }

  private static String required(String value, String label, int maxLength) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
    String normalized = value.strip();
    if (normalized.length() > maxLength || normalized.indexOf('\0') >= 0
        || "Branch name".equals(label) && (normalized.contains("\n") || normalized.contains("\r"))) {
      throw new IllegalArgumentException(label + " is invalid");
    }
    return normalized;
  }

  private static void requireSuccess(GitCommandResult result, String operation) {
    ProjectMirrorService.requireSuccess(result, operation);
  }

  private static ProjectWorktreeView view(ProjectWorktree worktree) {
    return new ProjectWorktreeView(
        worktree.id(), worktree.projectId(), worktree.gitRevision(), worktree.branchName(),
        worktree.upstreamBranch(), worktree.detached(), worktree.status(),
        worktree.createdAt(), worktree.removedAt());
  }
}
