package tech.wenisch.petri.git;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.git.provider.CreatePullRequest;
import tech.wenisch.petri.git.provider.CreatedPullRequest;
import tech.wenisch.petri.git.provider.GitAccountConnectionService;
import tech.wenisch.petri.git.provider.GitProviderInstanceRepository;
import tech.wenisch.petri.git.provider.GitProviderType;
import tech.wenisch.petri.git.provider.ProjectCredentialMode;
import tech.wenisch.petri.git.provider.ProjectProviderSource;
import tech.wenisch.petri.git.provider.ProjectProviderSourceService;

/** Opens pull requests for a branch worktree through the project's connected Git account. */
@Service
public class PullRequestService {
  private static final int PREVIEW_DIFF_CHARS = 1;

  private final WorkspaceGitService git;
  private final ProjectWorktreeService worktrees;
  private final ProjectProviderSourceService sources;
  private final GitProviderInstanceRepository instances;
  private final GitAccountConnectionService connections;
  private final AuditLogService audit;

  public PullRequestService(
      WorkspaceGitService git, ProjectWorktreeService worktrees,
      ProjectProviderSourceService sources, GitProviderInstanceRepository instances,
      GitAccountConnectionService connections, AuditLogService audit) {
    this.git = git;
    this.worktrees = worktrees;
    this.sources = sources;
    this.instances = instances;
    this.connections = connections;
    this.audit = audit;
  }

  /**
   * What the dialog needs before anything is created.
   *
   * @param unavailable why a pull request cannot be opened from here, or null
   * @param provider GITHUB, GITLAB or FORGEJO; decides which optional fields apply
   */
  public record Preview(
      String sourceBranch,
      String targetBranch,
      String defaultBranch,
      List<String> remoteBranches,
      boolean published,
      int unpushedCommits,
      int uncommittedChanges,
      List<WorkspaceGitService.PullRequestCommit> commits,
      List<WorkspaceGitService.PullRequestFile> files,
      String provider,
      String repository,
      String unavailable) {}

  public record Command(
      String title,
      String description,
      String target,
      boolean draft,
      boolean assignToMe,
      String labels,
      boolean squash,
      boolean removeSourceBranch) {}

  public Preview preview(UUID worktreeId, String target) {
    WorkspaceGitService.PullRequestContext context =
        git.pullRequestContext(worktreeId, target, PREVIEW_DIFF_CHARS);
    UUID projectId = worktrees.get(worktreeId).projectId();
    ProjectProviderSource source = sources.find(projectId);
    String unavailable = unavailableReason(source);
    String provider = source == null ? null : instances.findById(source.getProviderInstanceId())
        .map(value -> value.getProviderType().name()).orElse(null);
    return new Preview(context.sourceBranch(), context.targetBranch(), context.defaultBranch(),
        context.remoteBranches(), context.published(), context.unpushedCommits(),
        context.uncommittedChanges(), context.commits(), context.files(), provider,
        source == null ? null : source.getFullName(), unavailable);
  }

  public CreatedPullRequest create(UUID worktreeId, Command command, String actorEmail) {
    String title = command.title() == null ? "" : command.title().strip();
    if (title.isEmpty()) throw new IllegalArgumentException("A title is required");
    if (title.length() > 250) throw new IllegalArgumentException("The title is too long");
    String description = command.description() == null ? "" : command.description();
    if (description.length() > 60_000) throw new IllegalArgumentException("The description is too long");

    WorkspaceGitService.PullRequestContext context =
        git.pullRequestContext(worktreeId, command.target(), PREVIEW_DIFF_CHARS);
    UUID projectId = worktrees.get(worktreeId).projectId();
    ProjectProviderSource source = sources.find(projectId);
    String unavailable = unavailableReason(source);
    if (unavailable != null) throw new IllegalStateException(unavailable);
    if (!context.published() || context.unpushedCommits() > 0) {
      throw new IllegalStateException("Push " + context.sourceBranch()
          + " first: the pull request would not contain your latest commits");
    }
    if (context.commits().isEmpty()) {
      throw new IllegalStateException(context.sourceBranch() + " has no commits that "
          + context.targetBranch() + " lacks");
    }
    List<String> labels = command.labels() == null ? List.of()
        : Arrays.stream(command.labels().split(",")).map(String::strip)
            .filter(value -> !value.isEmpty()).distinct().limit(20).toList();
    if (labels.stream().anyMatch(value -> value.length() > 100)) {
      throw new IllegalArgumentException("A label is too long");
    }
    CreatedPullRequest created = connections.createPullRequest(
        source.getConnectionId(), source.getExternalRepositoryId(), source.getFullName(),
        new CreatePullRequest(title, description, context.sourceBranch(), context.targetBranch(),
            command.draft(), command.assignToMe(), labels, command.squash(),
            command.removeSourceBranch()));
    audit.record(actorEmail, "GIT_PULL_REQUEST_OPENED", "PROJECT_WORKTREE", worktreeId,
        "Opened pull request #" + created.number() + " from " + context.sourceBranch()
            + " into " + context.targetBranch());
    return created;
  }

  private static String unavailableReason(ProjectProviderSource source) {
    if (source == null) {
      return "Pull requests need a project imported from a connected Git account.";
    }
    if (source.getCredentialMode() != ProjectCredentialMode.CONNECTION) {
      return "This project uses its own repository credential; switch it back to the connected "
          + "Git account to open pull requests from Petri.";
    }
    if (!source.isCanWrite()) {
      return "The connected Git account has no write access to " + source.getFullName() + ".";
    }
    return null;
  }
}
