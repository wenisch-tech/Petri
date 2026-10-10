package tech.wenisch.petri.git;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;
import tech.wenisch.petri.workspace.WorkspacePaths;

@Service
public class ProjectMirrorService {
  private final ProjectRepository projects;
  private final ProjectGitCredentialService credentials;
  private final GitCommandRunner git;
  private final WorkspacePaths paths;
  private final AuditLogService auditLog;
  private final AppUserRepository users;

  public ProjectMirrorService(
      ProjectRepository projects,
      ProjectGitCredentialService credentials,
      GitCommandRunner git,
      WorkspacePaths paths,
      AuditLogService auditLog,
      AppUserRepository users) {
    this.projects = projects;
    this.credentials = credentials;
    this.git = git;
    this.paths = paths;
    this.auditLog = auditLog;
    this.users = users;
  }

  public ProjectMirrorStatus status(UUID projectId) {
    project(projectId);
    try {
      Path mirror = paths.mirror(projectId);
      return new ProjectMirrorStatus(isInitialized(mirror));
    } catch (IOException exception) {
      throw new GitOperationException("Could not inspect project mirror", exception);
    }
  }

  public ProjectMirrorStatus synchronize(UUID projectId, Principal actor) {
    return synchronize(projectId, actor.getName());
  }

  public ProjectMirrorStatus synchronize(UUID projectId, String actorEmail) {
    Project project = project(projectId);
    AppUser actor = actor(actorEmail);
    validateRepositoryLocation(project.getRepositoryUri());
    try {
      Path mirror = paths.mirror(projectId);
      boolean initialized = isInitialized(mirror);
      if (!initialized && directoryHasContent(mirror)) {
        throw new GitOperationException("Project mirror directory is not empty but has no Git mirror");
      }

      Optional<GitCredentialMaterial> credential = credentials.material(projectId);
      if (credential.isPresent() && !isHttps(project.getRepositoryUri())) {
        throw new GitOperationException("Stored credentials can only be used with HTTPS repositories");
      }
      GitCommandResult result = credential.isPresent()
          ? runAuthenticated(project, mirror, initialized, credential.orElseThrow())
          : run(project, mirror, initialized, Map.of(), List.of());
      if (!result.succeeded()) {
        String reason = result.timedOut() ? "Git operation timed out" : "Git operation failed";
        String details = result.output().isBlank() ? "" : ": " + result.output().strip();
        throw new GitOperationException(reason + details);
      }
      configureBranchRefs(project, mirror);
      auditLog.record(
          actor.getEmail(),
          initialized ? "PROJECT_MIRROR_FETCHED" : "PROJECT_MIRROR_CLONED",
          "PROJECT",
          projectId,
          (initialized ? "Fetched" : "Cloned") + " Git mirror for project " + project.getName());
      return new ProjectMirrorStatus(true);
    } catch (IOException exception) {
      throw new GitOperationException("Could not synchronize project mirror", exception);
    }
  }

  GitCommandResult runRemote(UUID projectId, Path workingDirectory, List<String> arguments)
      throws IOException {
    Project project = project(projectId);
    Optional<GitCredentialMaterial> credential = credentials.material(projectId);
    if (credential.isPresent() && !isHttps(project.getRepositoryUri())) {
      throw new GitOperationException("Stored credentials can only be used with HTTPS repositories");
    }
    if (credential.isEmpty()) return git.run(workingDirectory, arguments);
    GitCredentialMaterial material = credential.orElseThrow();
    try (GitAskPassSession askPass =
        new GitAskPassSession(paths.temporaryDirectory(), material)) {
      return git.run(workingDirectory, arguments, askPass.environment(), List.of(material.secret()));
    }
  }

  private GitCommandResult runAuthenticated(
      Project project,
      Path mirror,
      boolean initialized,
      GitCredentialMaterial credential) throws IOException {
    try (GitAskPassSession askPass =
        new GitAskPassSession(paths.temporaryDirectory(), credential)) {
      return run(
          project,
          mirror,
          initialized,
          askPass.environment(),
          List.of(credential.secret()));
    }
  }

  private GitCommandResult run(
      Project project,
      Path mirror,
      boolean initialized,
      Map<String, String> environment,
      List<String> secrets) throws IOException {
    if (!initialized) {
      return git.run(paths.root(), List.of(
          "clone", "--mirror", "--", project.getRepositoryUri(), mirror.toString()),
          environment, secrets);
    }
    String branch = project.getDefaultBranch();
    String localBefore = objectId(mirror, "refs/heads/" + branch);
    String remoteBefore = objectId(mirror, "refs/remotes/origin/" + branch);
    GitCommandResult result = git.run(paths.root(), List.of(
        "--git-dir=" + mirror, "fetch", "--prune", "origin"), environment, secrets);
    if (result.succeeded() && localBefore != null && localBefore.equals(remoteBefore)
        && !branchCheckedOut(mirror, branch)) {
      String remoteAfter = objectId(mirror, "refs/remotes/origin/" + branch);
      if (remoteAfter != null) {
        requireSuccess(git.run(paths.root(), List.of(
            "--git-dir=" + mirror, "update-ref", "refs/heads/" + branch,
            remoteAfter, localBefore)), "fast-forward default branch");
      }
    }
    return result;
  }

  private void configureBranchRefs(Project project, Path mirror) throws IOException {
    GitCommandResult mirrorMode = git.run(
        paths.root(), List.of("--git-dir=" + mirror, "config", "--get", "remote.origin.mirror"));
    if (mirrorMode.succeeded() && "true".equalsIgnoreCase(mirrorMode.output().strip())) {
      String symbolicHead = symbolicHeadBranch(mirror);
      GitCommandResult heads = git.run(paths.root(), List.of(
          "--git-dir=" + mirror, "for-each-ref", "--format=%(refname:short) %(objectname)",
          "refs/heads"));
      requireSuccess(heads, "inspect mirrored branches");
      for (String line : heads.output().lines().toList()) {
        int separator = line.lastIndexOf(' ');
        if (separator <= 0) continue;
        String branch = line.substring(0, separator);
        String object = line.substring(separator + 1);
        requireSuccess(git.run(paths.root(), List.of(
            "--git-dir=" + mirror, "update-ref", "refs/petri/mirror-backup/" + branch, object)),
            "back up mirrored branch " + branch);
        requireSuccess(git.run(paths.root(), List.of(
            "--git-dir=" + mirror, "update-ref", "refs/remotes/origin/" + branch, object)),
            "initialize remote branch " + branch);
        if (!branch.equals(project.getDefaultBranch()) && !branch.equals(symbolicHead)) {
          requireSuccess(git.run(paths.root(), List.of(
              "--git-dir=" + mirror, "update-ref", "-d", "refs/heads/" + branch)),
              "separate mirrored branch " + branch);
        }
      }
      requireSuccess(git.run(paths.root(), List.of(
          "--git-dir=" + mirror, "config", "remote.origin.mirror", "false")),
          "disable mirror ref updates");
    }
    git.run(paths.root(), List.of(
        "--git-dir=" + mirror, "config", "--unset-all", "remote.origin.fetch"));
    requireSuccess(git.run(paths.root(), List.of(
        "--git-dir=" + mirror, "config", "--add", "remote.origin.fetch",
        "+refs/heads/*:refs/remotes/origin/*")), "configure remote branch fetch");
    ensureDefaultLocalBranch(project, mirror);
  }

  private String symbolicHeadBranch(Path mirror) throws IOException {
    GitCommandResult result = git.run(paths.root(), List.of(
        "--git-dir=" + mirror, "symbolic-ref", "--quiet", "--short", "HEAD"));
    return result.succeeded() && !result.output().isBlank() ? result.output().strip() : null;
  }

  private String objectId(Path mirror, String reference) throws IOException {
    GitCommandResult result = git.run(paths.root(), List.of(
        "--git-dir=" + mirror, "rev-parse", "--verify", "--quiet", reference + "^{commit}"));
    return result.succeeded() && !result.output().isBlank() ? result.output().strip() : null;
  }

  private boolean branchCheckedOut(Path mirror, String branch) throws IOException {
    GitCommandResult result = git.run(paths.root(), List.of(
        "--git-dir=" + mirror, "worktree", "list", "--porcelain"));
    return result.succeeded() && result.output().contains("branch refs/heads/" + branch);
  }

  private void ensureDefaultLocalBranch(Project project, Path mirror) throws IOException {
    String branch = project.getDefaultBranch();
    GitCommandResult local = git.run(paths.root(), List.of(
        "--git-dir=" + mirror, "rev-parse", "--verify", "--quiet",
        "refs/heads/" + branch + "^{commit}"));
    if (local.succeeded()) {
      requireSuccess(git.run(paths.root(), List.of(
          "--git-dir=" + mirror, "config", "branch." + branch + ".remote", "origin")),
          "configure default branch remote");
      requireSuccess(git.run(paths.root(), List.of(
          "--git-dir=" + mirror, "config", "branch." + branch + ".merge", "refs/heads/" + branch)),
          "configure default branch upstream");
      return;
    }
    GitCommandResult remote = git.run(paths.root(), List.of(
        "--git-dir=" + mirror, "rev-parse", "--verify", "--quiet",
        "refs/remotes/origin/" + branch + "^{commit}"));
    if (!remote.succeeded()) return;
    requireSuccess(git.run(paths.root(), List.of(
        "--git-dir=" + mirror, "update-ref", "refs/heads/" + branch,
        remote.output().strip())), "create local default branch");
    requireSuccess(git.run(paths.root(), List.of(
        "--git-dir=" + mirror, "config", "branch." + branch + ".remote", "origin")),
        "configure default branch remote");
    requireSuccess(git.run(paths.root(), List.of(
        "--git-dir=" + mirror, "config", "branch." + branch + ".merge", "refs/heads/" + branch)),
        "configure default branch upstream");
  }

  static void requireSuccess(GitCommandResult result, String operation) {
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

  private static boolean isInitialized(Path mirror) {
    return Files.isRegularFile(mirror.resolve("HEAD"))
        && Files.isDirectory(mirror.resolve("objects"));
  }

  private static boolean directoryHasContent(Path directory) throws IOException {
    try (var children = Files.list(directory)) {
      return children.findAny().isPresent();
    }
  }

  private static void validateRepositoryLocation(String location) {
    if (location == null || location.isBlank() || location.startsWith("-")
        || location.indexOf('\0') >= 0 || location.contains("\n") || location.contains("\r")) {
      throw new GitOperationException("Repository location is invalid");
    }
    if (location.regionMatches(true, 0, "http://", 0, 7)
        || location.regionMatches(true, 0, "https://", 0, 8)) {
      try {
        if (new URI(location).getRawUserInfo() != null) {
          throw new GitOperationException("Repository URLs must not contain embedded credentials");
        }
      } catch (URISyntaxException exception) {
        throw new GitOperationException("Repository URL is invalid", exception);
      }
    }
  }

  private static boolean isHttps(String location) {
    return location.regionMatches(true, 0, "https://", 0, 8);
  }
}
