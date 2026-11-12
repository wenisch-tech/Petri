package tech.wenisch.petri.git;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
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
import tech.wenisch.petri.workspace.HostStat;
import tech.wenisch.petri.workspace.WorkspaceHost;
import tech.wenisch.petri.workspace.WorkspaceHostResolver;

@Service
public class ProjectMirrorService {
  private final ProjectRepository projects;
  private final ProjectGitCredentialResolver credentials;
  private final WorkspaceHostResolver hosts;
  private final AuditLogService auditLog;
  private final AppUserRepository users;

  public ProjectMirrorService(
      ProjectRepository projects,
      ProjectGitCredentialResolver credentials,
      WorkspaceHostResolver hosts,
      AuditLogService auditLog,
      AppUserRepository users) {
    this.projects = projects;
    this.credentials = credentials;
    this.hosts = hosts;
    this.auditLog = auditLog;
    this.users = users;
  }

  public ProjectMirrorStatus status(UUID projectId) {
    project(projectId);
    try {
      WorkspaceHost host = hosts.forProject(projectId);
      Path mirror = host.layout().mirror(projectId);
      return new ProjectMirrorStatus(isInitialized(host, mirror));
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
      WorkspaceHost host = hosts.forProject(projectId);
      Path mirror = host.layout().mirror(projectId);
      boolean initialized = isInitialized(host, mirror);
      if (!initialized && !host.layout().isEmptyDirectory(mirror)) {
        throw new GitOperationException("Project mirror directory is not empty but has no Git mirror");
      }

      Optional<GitCredentialMaterial> credential = credentials.material(projectId);
      if (credential.isPresent() && !isHttps(project.getRepositoryUri())) {
        throw new GitOperationException("Stored credentials can only be used with HTTPS repositories");
      }
      GitCommandResult result = run(host, project, mirror, initialized, credential);
      if (!result.succeeded()) {
        String reason = result.timedOut() ? "Git operation timed out" : "Git operation failed";
        String details = result.output().isBlank() ? "" : ": " + result.output().strip();
        throw new GitOperationException(reason + details);
      }
      configureBranchRefs(host, project, mirror, initialized);
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

  private static final java.util.regex.Pattern PROVIDER_PULL_REF =
      java.util.regex.Pattern.compile("refs/(pull|merge-requests)/[0-9]{1,9}/head");
  private static final java.util.regex.Pattern LOCAL_PULL_REF =
      java.util.regex.Pattern.compile("refs/petri/pr/[0-9]{1,9}");

  /**
   * Fetches a provider's pull request head (which also covers forks) into a local Petri ref, using
   * the project's stored or connected-account credentials.
   */
  public void fetchPullRequestRef(UUID projectId, String providerRef, String localRef) {
    if (!PROVIDER_PULL_REF.matcher(providerRef).matches()
        || !LOCAL_PULL_REF.matcher(localRef).matches()) {
      throw new IllegalArgumentException("Invalid pull request ref");
    }
    try {
      WorkspaceHost host = hosts.forProject(projectId);
      Path mirror = host.layout().mirror(projectId);
      requireSuccess(runRemote(projectId, host.layout().root(), List.of(
          "--git-dir=" + mirror, "fetch", "--no-tags", "origin",
          "+" + providerRef + ":" + localRef)), "fetch pull request");
    } catch (IOException exception) {
      throw new GitOperationException("Could not fetch pull request", exception);
    }
  }

  GitCommandResult runRemote(UUID projectId, Path workingDirectory, List<String> arguments)
      throws IOException {
    Project project = project(projectId);
    Optional<GitCredentialMaterial> credential = credentials.material(projectId);
    if (credential.isPresent() && !isHttps(project.getRepositoryUri())) {
      throw new GitOperationException("Stored credentials can only be used with HTTPS repositories");
    }
    WorkspaceHost host = hosts.forProject(projectId);
    if (credential.isEmpty()) return host.git().run(workingDirectory, arguments);
    return host.git().runWithCredential(workingDirectory, arguments, credential.orElseThrow());
  }

  private GitCommandResult run(
      WorkspaceHost host,
      Project project,
      Path mirror,
      boolean initialized,
      Optional<GitCredentialMaterial> credential) throws IOException {
    if (!initialized) {
      return network(host, credential, List.of(
          "clone", "--bare",
          "--config", "remote.origin.mirror=false",
          "--config", "remote.origin.fetch=+refs/heads/*:refs/remotes/origin/*",
          "--", project.getRepositoryUri(), mirror.toString()));
    }
    String branch = project.getDefaultBranch();
    String localBefore = objectId(host, mirror, "refs/heads/" + branch);
    String remoteBefore = objectId(host, mirror, "refs/remotes/origin/" + branch);
    GitCommandResult result = network(host, credential, List.of(
        "--git-dir=" + mirror, "fetch", "--prune", "origin"));
    if (result.succeeded() && localBefore != null && localBefore.equals(remoteBefore)
        && !branchCheckedOut(host, mirror, branch)) {
      String remoteAfter = objectId(host, mirror, "refs/remotes/origin/" + branch);
      if (remoteAfter != null) {
        requireSuccess(host.git().run(host.layout().root(), List.of(
            "--git-dir=" + mirror, "update-ref", "refs/heads/" + branch,
            remoteAfter, localBefore)), "fast-forward default branch");
      }
    }
    return result;
  }

  private static GitCommandResult network(
      WorkspaceHost host, Optional<GitCredentialMaterial> credential, List<String> arguments)
      throws IOException {
    return credential.isPresent()
        ? host.git().runWithCredential(host.layout().root(), arguments, credential.orElseThrow())
        : host.git().run(host.layout().root(), arguments);
  }

  private void configureBranchRefs(WorkspaceHost host, 
      Project project, Path mirror, boolean previouslyInitialized) throws IOException {
    GitCommandResult mirrorMode = host.git().run(
        host.layout().root(), List.of("--git-dir=" + mirror, "config", "--get", "remote.origin.mirror"));
    if (mirrorMode.succeeded() && "true".equalsIgnoreCase(mirrorMode.output().strip())) {
      String symbolicHead = symbolicHeadBranch(host, mirror);
      GitCommandResult heads = host.git().run(host.layout().root(), List.of(
          "--git-dir=" + mirror, "for-each-ref", "--format=%(refname:short) %(objectname)",
          "refs/heads"));
      requireSuccess(heads, "inspect mirrored branches");
      for (String line : heads.output().lines().toList()) {
        int separator = line.lastIndexOf(' ');
        if (separator <= 0) continue;
        String branch = line.substring(0, separator);
        String object = line.substring(separator + 1);
        requireSuccess(host.git().run(host.layout().root(), List.of(
            "--git-dir=" + mirror, "update-ref", "refs/petri/mirror-backup/" + branch, object)),
            "back up mirrored branch " + branch);
        requireSuccess(host.git().run(host.layout().root(), List.of(
            "--git-dir=" + mirror, "update-ref", "refs/remotes/origin/" + branch, object)),
            "initialize remote branch " + branch);
        if (!branch.equals(project.getDefaultBranch()) && !branch.equals(symbolicHead)) {
          requireSuccess(host.git().run(host.layout().root(), List.of(
              "--git-dir=" + mirror, "update-ref", "-d", "refs/heads/" + branch)),
              "separate mirrored branch " + branch);
        }
      }
      requireSuccess(host.git().run(host.layout().root(), List.of(
          "--git-dir=" + mirror, "config", "remote.origin.mirror", "false")),
          "disable mirror ref updates");
    }
    host.git().run(host.layout().root(), List.of(
        "--git-dir=" + mirror, "config", "--unset-all", "remote.origin.fetch"));
    requireSuccess(host.git().run(host.layout().root(), List.of(
        "--git-dir=" + mirror, "config", "--add", "remote.origin.fetch",
        "+refs/heads/*:refs/remotes/origin/*")), "configure remote branch fetch");
    ensureDefaultLocalBranch(host, project, mirror);
    if (!previouslyInitialized) separateInitialLocalBranches(host, project, mirror);
  }

  private void separateInitialLocalBranches(WorkspaceHost host, Project project, Path mirror) throws IOException {
    if (objectId(host, mirror, "refs/heads/" + project.getDefaultBranch()) == null) return;
    GitCommandResult heads = host.git().run(host.layout().root(), List.of(
        "--git-dir=" + mirror, "for-each-ref", "--format=%(refname:short)", "refs/heads"));
    requireSuccess(heads, "inspect initial local branches");
    for (String branch : heads.output().lines().map(String::strip).toList()) {
      if (branch.isBlank() || branch.equals(project.getDefaultBranch())) continue;
      requireSuccess(host.git().run(host.layout().root(), List.of(
          "--git-dir=" + mirror, "update-ref", "-d", "refs/heads/" + branch)),
          "separate initial branch " + branch);
    }
  }

  private String symbolicHeadBranch(WorkspaceHost host, Path mirror) throws IOException {
    GitCommandResult result = host.git().run(host.layout().root(), List.of(
        "--git-dir=" + mirror, "symbolic-ref", "--quiet", "--short", "HEAD"));
    return result.succeeded() && !result.output().isBlank() ? result.output().strip() : null;
  }

  private String objectId(WorkspaceHost host, Path mirror, String reference) throws IOException {
    GitCommandResult result = host.git().run(host.layout().root(), List.of(
        "--git-dir=" + mirror, "rev-parse", "--verify", "--quiet", reference + "^{commit}"));
    return result.succeeded() && !result.output().isBlank() ? result.output().strip() : null;
  }

  private boolean branchCheckedOut(WorkspaceHost host, Path mirror, String branch) throws IOException {
    GitCommandResult result = host.git().run(host.layout().root(), List.of(
        "--git-dir=" + mirror, "worktree", "list", "--porcelain"));
    return result.succeeded() && result.output().contains("branch refs/heads/" + branch);
  }

  private void ensureDefaultLocalBranch(WorkspaceHost host, Project project, Path mirror) throws IOException {
    String branch = project.getDefaultBranch();
    GitCommandResult local = host.git().run(host.layout().root(), List.of(
        "--git-dir=" + mirror, "rev-parse", "--verify", "--quiet",
        "refs/heads/" + branch + "^{commit}"));
    if (local.succeeded()) {
      requireSuccess(host.git().run(host.layout().root(), List.of(
          "--git-dir=" + mirror, "config", "branch." + branch + ".remote", "origin")),
          "configure default branch remote");
      requireSuccess(host.git().run(host.layout().root(), List.of(
          "--git-dir=" + mirror, "config", "branch." + branch + ".merge", "refs/heads/" + branch)),
          "configure default branch upstream");
      return;
    }
    GitCommandResult remote = host.git().run(host.layout().root(), List.of(
        "--git-dir=" + mirror, "rev-parse", "--verify", "--quiet",
        "refs/remotes/origin/" + branch + "^{commit}"));
    if (!remote.succeeded()) return;
    requireSuccess(host.git().run(host.layout().root(), List.of(
        "--git-dir=" + mirror, "update-ref", "refs/heads/" + branch,
        remote.output().strip())), "create local default branch");
    requireSuccess(host.git().run(host.layout().root(), List.of(
        "--git-dir=" + mirror, "config", "branch." + branch + ".remote", "origin")),
        "configure default branch remote");
    requireSuccess(host.git().run(host.layout().root(), List.of(
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

  private static boolean isInitialized(WorkspaceHost host, Path mirror) throws IOException {
    HostStat head = host.layout().stat(mirror.resolve("HEAD"));
    HostStat objects = host.layout().stat(mirror.resolve("objects"));
    return head.isFile() && objects.isDirectory();
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
