package tech.wenisch.petri.git.provider;

import java.time.Instant;

/**
 * One CI/CD run of a repository, normalised across providers.
 *
 * @param status SUCCESS, FAILED, RUNNING, PENDING, CANCELLED, SKIPPED or UNKNOWN
 * @param detail the provider's own wording of the status or conclusion
 * @param event what triggered it (push, pull_request, schedule, ...)
 */
public record PipelineRun(
    String id,
    String name,
    String status,
    String detail,
    String ref,
    String sha,
    String event,
    String webUrl,
    Instant createdAt,
    Instant updatedAt,
    Long pullRequest,
    String pullRequestTitle) {

  public PipelineRun(
      String id, String name, String status, String detail, String ref, String sha, String event,
      String webUrl, Instant createdAt, Instant updatedAt) {
    this(id, name, status, detail, ref, sha, event, webUrl, createdAt, updatedAt, null, null);
  }

  /** The same run, tied to the pull request it belongs to. */
  public PipelineRun withPullRequest(long number, String title) {
    return new PipelineRun(
        id, name, status, detail, ref, sha, event, webUrl, createdAt, updatedAt, number, title);
  }
}
