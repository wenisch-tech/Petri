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
    List<String> arguments = initialized
        ? List.of("--git-dir=" + mirror, "remote", "update", "--prune")
        : List.of("clone", "--mirror", "--", project.getRepositoryUri(), mirror.toString());
    return git.run(paths.root(), arguments, environment, secrets);
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
