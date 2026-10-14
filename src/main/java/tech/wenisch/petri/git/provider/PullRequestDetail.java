package tech.wenisch.petri.git.provider;

import java.time.Instant;
import java.util.List;

/** One pull or merge request with its description, discussion and changed files. */
public record PullRequestDetail(
    PullRequestSummary summary,
    String description,
    String headSha,
    int additions,
    int deletions,
    int commits,
    List<ChangedFile> files,
    List<Comment> comments) {

  /** A file touched by the pull request; counts are 0 when the provider does not report them. */
  public record ChangedFile(
      String path, String previousPath, String status, int additions, int deletions) {

    public String name() {
      int slash = path == null ? -1 : path.lastIndexOf('/');
      return slash < 0 ? path : path.substring(slash + 1);
    }

    public String directory() {
      int slash = path == null ? -1 : path.lastIndexOf('/');
      return slash < 0 ? "" : path.substring(0, slash);
    }
  }

  /**
   * A conversation entry. {@code review} is set for review verdicts (APPROVED, CHANGES_REQUESTED,
   * COMMENTED); plain comments leave it null.
   */
  public record Comment(
      String author, String body, Instant createdAt, String review, String webUrl) {}
}
