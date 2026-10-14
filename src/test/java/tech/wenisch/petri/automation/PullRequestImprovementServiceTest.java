package tech.wenisch.petri.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.git.provider.PullRequestRef;

class PullRequestImprovementServiceTest {
  @Test
  void theWorkflowPromptCarriesOnlyTheFindingsAndStaysInScope() {
    PullRequestRef pr = new PullRequestRef(7, "Add cache", "Body", "alice", "abc123", "feature",
        "main", "refs/pull/7/head", false, false, "https://git.test/pull/7", Instant.now());
    var finding = new ReviewSubmission.Finding("src/A.java", 12, ReviewSubmission.Severity.MAJOR,
        "Null check missing", "value can be null here", "if (value == null) return;", null);

    String prompt = PullRequestImprovementService.prompt(null, pr, List.of(finding));

    assertThat(prompt).contains("pull request #7", "feature", "abc123", "[MAJOR] Null check missing",
        "src/A.java:12", "Suggested replacement", "Do not add features");
  }

  @Test
  void forkPullRequestsAreOnlyPushableWhenTheAuthorAllowsMaintainerEdits() {
    PullRequestRef sameRepository = new PullRequestRef(1, "t", "b", "a", "s", "f", "main",
        "refs/pull/1/head", false, false, null, null);
    PullRequestRef fork = new PullRequestRef(2, "t", "b", "a", "s", "f", "main",
        "refs/pull/2/head", false, true, null, null);
    PullRequestRef editableFork = new PullRequestRef(3, "t", "b", "a", "s", "f", "main",
        "refs/pull/3/head", false, true, null, null, "https://github.test/fork/demo.git", true);

    assertThat(sameRepository.pushable()).isTrue();
    assertThat(fork.pushable()).isFalse();
    assertThat(editableFork.pushable()).isTrue();
  }
}
