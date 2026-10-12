package tech.wenisch.petri.git.provider;

import java.time.Instant;

/**
 * A pull request (GitLab: merge request) as listed by a provider.
 *
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
    Instant updatedAt) {}
