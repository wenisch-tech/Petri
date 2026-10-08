package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.petri.agent.change.ChangeProposalService;
import tech.wenisch.petri.agent.change.ChangeProposalView;
import tech.wenisch.petri.agent.run.TestRunService;
import tech.wenisch.petri.agent.run.TestRunView;

@RestController
@RequestMapping("/operations")
public class AgentOperationController {
  private final ChangeProposalService changes;
  private final TestRunService testRuns;

  public AgentOperationController(ChangeProposalService changes, TestRunService testRuns) {
    this.changes = changes;
    this.testRuns = testRuns;
  }

  @PostMapping("/change-proposals/{proposalId}/approve")
  public ChangeProposalView approveChange(@PathVariable UUID proposalId, Principal principal) {
    return changes.approve(proposalId, principal.getName());
  }

  @PostMapping("/change-proposals/{proposalId}/reject")
  public ChangeProposalView rejectChange(@PathVariable UUID proposalId, Principal principal) {
    return changes.reject(proposalId, principal.getName());
  }

  @PostMapping("/change-proposals/{proposalId}/apply")
  public ChangeProposalView applyChange(@PathVariable UUID proposalId) {
    return changes.apply(proposalId);
  }

  @PostMapping("/test-runs/{testRunId}/approve")
  public TestRunView approveTestRun(@PathVariable UUID testRunId, Principal principal) {
    return testRuns.approve(testRunId, principal.getName());
  }

  @PostMapping("/test-runs/{testRunId}/reject")
  public TestRunView rejectTestRun(@PathVariable UUID testRunId, Principal principal) {
    return testRuns.reject(testRunId, principal.getName());
  }

  @PostMapping("/test-runs/{testRunId}/execute")
  public TestRunView executeTestRun(@PathVariable UUID testRunId) {
    return testRuns.execute(testRunId);
  }
}
