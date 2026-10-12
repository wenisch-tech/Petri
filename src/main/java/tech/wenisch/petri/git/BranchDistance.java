package tech.wenisch.petri.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Commits ahead of and behind the upstream. When the upstream does not exist (a branch that was
 * never pushed, or whose configured upstream was not created), "ahead" counts the commits that are
 * on no remote branch yet, so a fresh commit shows as {@code ↑1} instead of {@code ↑0}.
 */
record BranchDistance(int ahead, int behind) {
  static BranchDistance of(GitCommandRunner git, Path root, String upstream) {
    if (upstream != null) {
      BranchDistance distance = compare(git, root, upstream);
      if (distance != null) return distance;
    }
    return new BranchDistance(unpublished(git, root), 0);
  }

  private static BranchDistance compare(GitCommandRunner git, Path root, String upstream) {
    GitCommandResult result = run(git, root, List.of(
        "rev-list", "--left-right", "--count", "HEAD..." + upstream));
    if (result == null || !result.succeeded()) return null;
    String[] values = result.output().strip().split("\\s+");
    if (values.length != 2) return null;
    try {
      return new BranchDistance(Integer.parseInt(values[0]), Integer.parseInt(values[1]));
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static int unpublished(GitCommandRunner git, Path root) {
    GitCommandResult result = run(git, root, List.of(
        "rev-list", "--count", "HEAD", "--not", "--remotes"));
    if (result == null || !result.succeeded()) return 0;
    try {
      return Integer.parseInt(result.output().strip());
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static GitCommandResult run(GitCommandRunner git, Path root, List<String> arguments) {
    try {
      return git.run(root, arguments);
    } catch (IOException exception) {
      return null;
    }
  }
}
