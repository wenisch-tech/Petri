package tech.wenisch.petri.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;
import tech.wenisch.petri.workspace.WorkspacePaths;

@Service
public class ProjectWorktreeService {
  private final ProjectWorktreeRepository worktrees;
  private final ProjectRepository projects;
  private final AppUserRepository users;
  private final ProjectMirrorService mirrors;
  private final GitCommandRunner git;
  private final WorkspacePaths paths;
  private final AuditLogService auditLog;
  private final Clock clock;

  @Autowired
  public ProjectWorktreeService(
      ProjectWorktreeRepository worktrees,
      ProjectRepository projects,
      AppUserRepository users,
      ProjectMirrorService mirrors,
      GitCommandRunner git,
      WorkspacePaths paths,
      AuditLogService auditLog) {
    this(worktrees, projects, users, mirrors, git, paths, auditLog, Clock.systemUTC());
  }

  ProjectWorktreeService(
      ProjectWorktreeRepository worktrees,
      ProjectRepository projects,
      AppUserRepository users,
      ProjectMirrorService mirrors,
      GitCommandRunner git,
      WorkspacePaths paths,
      AuditLogService auditLog,
      Clock clock) {
    this.worktrees = worktrees;
    this.projects = projects;
    this.users = users;
    this.mirrors = mirrors;
    this.git = git;
    this.paths = paths;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<ProjectWorktreeView> list(UUID projectId) {
    project(projectId);
    return worktrees.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
        .map(ProjectWorktreeService::view)
        .toList();
  }

  @Transactional(readOnly = true)
  public ProjectWorktreeView get(UUID worktreeId) {
    return worktrees.findById(worktreeId)
        .map(ProjectWorktreeService::view)
        .orElseThrow(() -> new IllegalArgumentException("Unknown worktree"));
  }

  @Transactional
  public ProjectWorktreeView create(UUID projectId, String requestedRevision, String actorEmail) {
    Project project = project(projectId);
    AppUser actor = actor(actorEmail);
    if (!mirrors.status(projectId).initialized()) {
      throw new GitOperationException("Synchronize the project mirror before creating a worktree");
    }
    UUID runId = UUID.randomUUID();
    try {
      Path mirror = paths.mirror(projectId);
      String revision = resolveRevision(
          mirror, requestedRevision, project.getDefaultBranch());
      Path worktree = paths.runWorktree(projectId, runId);
      String relativePath = paths.root().relativize(worktree).toString();
      GitCommandResult result = git.run(
          paths.root(),
          List.of(
              "--git-dir=" + mirror,
              "worktree", "add", "--detach", "--", worktree.toString(), revision));
      requireSuccess(result, "create worktree");
      Instant now = Instant.now(clock);
      ProjectWorktree record = worktrees.save(new ProjectWorktree(
          runId, projectId, revision, relativePath, actor.getId(), now));
      auditLog.record(
          actor.getEmail(), "PROJECT_WORKTREE_CREATED", "PROJECT_WORKTREE", runId,
          "Created worktree for project " + project.getName() + " at revision " + revision);
      return view(record);
    } catch (IOException exception) {
      throw new GitOperationException("Could not create project worktree", exception);
    }
  }

  @Transactional
  public ProjectWorktreeView remove(UUID worktreeId, String actorEmail) {
    return remove(worktreeId, null, actorEmail);
  }

  @Transactional
  public ProjectWorktreeView remove(
      UUID worktreeId, UUID expectedProjectId, String actorEmail) {
    ProjectWorktree record = worktrees.findById(worktreeId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown worktree"));
    if (expectedProjectId != null && !expectedProjectId.equals(record.projectId())) {
      throw new IllegalArgumentException("Worktree belongs to another project");
    }
    if (record.status() == ProjectWorktreeStatus.REMOVED) return view(record);
    Project project = project(record.projectId());
    AppUser actor = actor(actorEmail);
    try {
      Path expected = paths.runWorktree(record.projectId(), record.id());
      String expectedRelative = paths.root().relativize(expected).toString();
      if (!expectedRelative.equals(record.relativePath())) {
        throw new GitOperationException("Recorded worktree path does not match its safe derived path");
      }
      Path mirror = paths.mirror(record.projectId());
      if (Files.exists(expected, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(expected)) {
          throw new GitOperationException("Refusing to remove a symbolic-link worktree path");
        }
        Path safeWorktree = paths.requireExistingPath(expected);
        GitCommandResult result = git.run(
            paths.root(),
            List.of(
                "--git-dir=" + mirror,
                "worktree", "remove", "--force", "--", safeWorktree.toString()));
        requireSuccess(result, "remove worktree");
      }
      record.removed(actor.getId(), Instant.now(clock));
      auditLog.record(
          actor.getEmail(), "PROJECT_WORKTREE_REMOVED", "PROJECT_WORKTREE", record.id(),
          "Removed worktree for project " + project.getName());
      return view(record);
    } catch (IOException exception) {
      throw new GitOperationException("Could not remove project worktree", exception);
    }
  }

  private static void requireSuccess(GitCommandResult result, String operation) {
    if (result.succeeded()) return;
    String reason = result.timedOut() ? "Git timed out while trying to " : "Git failed to ";
    String details = result.output().isBlank() ? "" : ": " + result.output().strip();
    throw new GitOperationException(reason + operation + details);
  }

  private Project project(UUID projectId) {
    return projects.findById(projectId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown project"));
  }

  private AppUser actor(String actorEmail) {
    return users.findByEmailIgnoreCase(actorEmail)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  private static String revision(String requested, String fallback) {
    String value = requested == null || requested.isBlank() ? fallback : requested.trim();
    if (value == null || value.isBlank() || value.length() > 255
        || value.indexOf('\0') >= 0 || value.contains("\n") || value.contains("\r")) {
      throw new IllegalArgumentException("Git revision is invalid");
    }
    return value;
  }

  private String resolveRevision(
      Path mirror, String requestedRevision, String configuredDefault) throws IOException {
    if (requestedRevision != null && !requestedRevision.isBlank()) {
      return revision(requestedRevision, null);
    }
    String configured = revision(null, configuredDefault);
    if (referenceExists(mirror, configured)) return configured;

    GitCommandResult symbolicHead = git.run(
        paths.root(),
        List.of("--git-dir=" + mirror, "symbolic-ref", "--quiet", "--short", "HEAD"));
    if (symbolicHead.succeeded() && !symbolicHead.output().isBlank()) {
      String actualDefault = revision(symbolicHead.output().strip(), null);
      if (referenceExists(mirror, actualDefault)) return actualDefault;
    }
    if (referenceExists(mirror, "HEAD")) return "HEAD";
    throw new GitOperationException(
        "Project mirror has no resolvable default revision; configure an existing branch");
  }

  private boolean referenceExists(Path mirror, String revision) throws IOException {
    GitCommandResult result = git.run(
        paths.root(),
        List.of(
            "--git-dir=" + mirror,
            "rev-parse",
            "--verify",
            "--quiet",
            "--end-of-options",
            revision + "^{commit}"));
    return result.succeeded();
  }

  private static ProjectWorktreeView view(ProjectWorktree worktree) {
    return new ProjectWorktreeView(
        worktree.id(), worktree.projectId(), worktree.gitRevision(), worktree.status(),
        worktree.createdAt(), worktree.removedAt());
  }
}
