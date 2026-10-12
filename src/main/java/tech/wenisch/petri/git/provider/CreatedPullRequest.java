package tech.wenisch.petri.git.provider;

import java.util.List;

/** The opened pull request; {@code warnings} lists optional steps (labels, assignee) that failed. */
public record CreatedPullRequest(long number, String webUrl, List<String> warnings) {
  public CreatedPullRequest {
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
