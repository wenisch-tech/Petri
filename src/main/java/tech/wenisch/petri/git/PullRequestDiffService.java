package tech.wenisch.petri.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.workspace.WorkspacePaths;

/** Read-only diff of a fetched pull request against its base branch, taken from the mirror. */
@Service
public class PullRequestDiffService {
  private static final Pattern BASE_BRANCH = Pattern.compile("[A-Za-z0-9._][A-Za-z0-9._/-]{0,254}");

  private final WorkspacePaths paths;
  private final GitCommandRunner git;

  public PullRequestDiffService(WorkspacePaths paths, GitCommandRunner git) {
    this.paths = paths;
    this.git = git;
  }

  /**
   * @param headSha commit the local {@code refs/petri/pr/<n>} ref resolved to
   * @param diff unified diff, cut at the requested size
   */
  public record PullRequestDiff(
      String headSha, List<String> changedFiles, String diff, boolean truncated) {}

  public PullRequestDiff diff(UUID projectId, long number, String baseBranch, int maxBytes) {
    if (!BASE_BRANCH.matcher(baseBranch).matches() || baseBranch.contains("..")
        || baseBranch.endsWith("/") || baseBranch.endsWith(".lock")) {
      throw new IllegalArgumentException("Base branch name is invalid");
    }
    try {
      Path mirror = paths.mirror(projectId);
      String head = "refs/petri/pr/" + number;
      GitCommandResult sha = git.run(paths.root(), List.of(
          "--git-dir=" + mirror, "rev-parse", "--verify", "--quiet", head + "^{commit}"));
      if (!sha.succeeded() || sha.output().isBlank()) {
        throw new GitOperationException("Pull request head was not fetched");
      }
      String base = "refs/remotes/origin/" + baseBranch;
      GitCommandResult baseCheck = git.run(paths.root(), List.of(
          "--git-dir=" + mirror, "rev-parse", "--verify", "--quiet", base + "^{commit}"));
      if (!baseCheck.succeeded()) {
        throw new GitOperationException("Base branch " + baseBranch + " is not in the mirror");
      }
      String range = base + "..." + head;
      GitCommandResult names = git.run(paths.root(), List.of(
          "--git-dir=" + mirror, "diff", "--no-color", "--no-ext-diff", "--no-textconv",
          "--name-only", "-z", range, "--"));
      requireSuccess(names, "list changed files");
      List<String> files = Arrays.stream(names.output().split("\\x00"))
          .filter(value -> !value.isBlank()).toList();
      GitCommandResult diff = git.run(paths.root(), List.of(
          "--git-dir=" + mirror, "diff", "--no-color", "--no-ext-diff", "--no-textconv",
          "--unified=3", range, "--"));
      requireSuccess(diff, "diff pull request");
      String text = diff.output();
      boolean truncated = diff.truncated() || text.length() > maxBytes;
      if (text.length() > maxBytes) text = text.substring(0, maxBytes);
      return new PullRequestDiff(sha.output().strip(), files, text, truncated);
    } catch (IOException exception) {
      throw new GitOperationException("Could not diff pull request", exception);
    }
  }

  /**
   * Changes between an earlier head commit and the fetched head of the pull request. Null when the
   * earlier commit is no longer in the mirror, e.g. after a force push.
   */
  public String sinceCommit(UUID projectId, long number, String previousSha, int maxBytes) {
    if (previousSha == null || !previousSha.matches("[0-9a-f]{7,64}")) return null;
    try {
      Path mirror = paths.mirror(projectId);
      GitCommandResult known = git.run(paths.root(), List.of(
          "--git-dir=" + mirror, "rev-parse", "--verify", "--quiet", previousSha + "^{commit}"));
      if (!known.succeeded() || known.output().isBlank()) return null;
      GitCommandResult diff = git.run(paths.root(), List.of(
          "--git-dir=" + mirror, "diff", "--no-color", "--no-ext-diff", "--no-textconv",
          "--unified=3", previousSha, "refs/petri/pr/" + number, "--"));
      if (!diff.succeeded()) return null;
      String text = diff.output();
      return text.length() > maxBytes ? text.substring(0, maxBytes) : text;
    } catch (IOException exception) {
      throw new GitOperationException("Could not diff pull request updates", exception);
    }
  }

  private static void requireSuccess(GitCommandResult result, String operation) {
    if (result.succeeded()) return;
    String details = result.output().isBlank() ? "" : ": " + result.output().strip();
    throw new GitOperationException("Git failed to " + operation + details);
  }
}
