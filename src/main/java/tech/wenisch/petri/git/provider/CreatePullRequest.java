package tech.wenisch.petri.git.provider;

import java.util.List;

/**
 * What a provider needs to open a pull request (GitLab: merge request).
 *
 * @param head source branch in the same repository
 * @param base target branch
 * @param squash GitLab only: squash commits when merging
 * @param removeSourceBranch GitLab only: delete the source branch after merging
 */
public record CreatePullRequest(
    String title,
    String body,
    String head,
    String base,
    boolean draft,
    boolean assignToMe,
    List<String> labels,
    boolean squash,
    boolean removeSourceBranch) {
  public CreatePullRequest {
    labels = labels == null ? List.of() : List.copyOf(labels);
  }
}
