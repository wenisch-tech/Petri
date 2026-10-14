package tech.wenisch.petri.git.provider;

import java.time.Instant;

/** A pull or merge request as the pull request view lists it, in any state. */
public record PullRequestSummary(
    long number,
    String title,
    String authorLogin,
    String headRef,
    String baseRef,
    State state,
    boolean draft,
    String webUrl,
    Instant createdAt,
    Instant updatedAt,
    String ci) {

  public PullRequestSummary(
      long number, String title, String authorLogin, String headRef, String baseRef, State state,
      boolean draft, String webUrl, Instant createdAt, Instant updatedAt) {
    this(number, title, authorLogin, headRef, baseRef, state, draft, webUrl, createdAt, updatedAt, null);
  }

  /** The same pull request with the status of the latest pipeline run on its branch. */
  public PullRequestSummary withCi(String status) {
    return new PullRequestSummary(
        number, title, authorLogin, headRef, baseRef, state, draft, webUrl, createdAt, updatedAt, status);
  }

  public enum State { OPEN, MERGED, CLOSED }
}
