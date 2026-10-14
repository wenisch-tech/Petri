package tech.wenisch.petri.git.provider;

import java.time.Instant;

/**
 * A pull request (GitLab: merge request) as listed by a provider.
 *
 * @param headCloneUrl HTTPS clone URL of the head repository, when the provider reports it
 * @param maintainerCanModify the author allows pushes to the head branch of a fork pull request
 * @param headFetchRef ref the provider exposes for the head commit, e.g. {@code refs/pull/7/head}
 * @param fromFork the head lives in another repository than the base
 */
public record PullRequestRef(
    long number,
    String title,
    String body,
    String authorLogin,
    String headSha,
    String headRef,
    String baseRef,
    String headFetchRef,
    boolean draft,
    boolean fromFork,
    String webUrl,
    Instant updatedAt,
    String headCloneUrl,
    boolean maintainerCanModify) {

  public PullRequestRef(
      long number, String title, String body, String authorLogin, String headSha, String headRef,
      String baseRef, String headFetchRef, boolean draft, boolean fromFork, String webUrl,
      Instant updatedAt) {
    this(number, title, body, authorLogin, headSha, headRef, baseRef, headFetchRef, draft, fromFork,
        webUrl, updatedAt, null, false);
  }

  /** Whether commits can be pushed to the head branch of this pull request. */
  public boolean pushable() {
    return !fromFork || (headCloneUrl != null && maintainerCanModify);
  }
}
