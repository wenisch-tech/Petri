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
import tech.wenisch.petri.project.ProjectAccessService;

@RestController
@RequestMapping("/operations")
public class AgentOperationController {
  private final ChangeProposalService changes;
  private final TestRunService testRuns;
  private final ProjectAccessService access;

  public AgentOperationController(
      ChangeProposalService changes, TestRunService testRuns, ProjectAccessService access) {
    this.changes = changes;
    this.testRuns = testRuns;
    this.access = access;
  }

  @PostMapping("/change-proposals/{proposalId}/approve")
  public ChangeProposalView approveChange(@PathVariable UUID proposalId, Principal principal) {
    access.requireWorktree(changes.get(proposalId).worktreeId(), principal.getName());
    return changes.approve(proposalId, principal.getName());
  }

  @PostMapping("/change-proposals/{proposalId}/reject")
  public ChangeProposalView rejectChange(@PathVariable UUID proposalId, Principal principal) {
    access.requireWorktree(changes.get(proposalId).worktreeId(), principal.getName());
    return changes.reject(proposalId, principal.getName());
  }

  @PostMapping("/change-proposals/{proposalId}/apply")
  public ChangeProposalView applyChange(@PathVariable UUID proposalId, Principal principal) {
    access.requireWorktree(changes.get(proposalId).worktreeId(), principal.getName());
    return changes.apply(proposalId);
  }

  @PostMapping("/test-runs/{testRunId}/approve")
  public TestRunView approveTestRun(@PathVariable UUID testRunId, Principal principal) {
    access.requireWorktree(testRuns.get(testRunId).worktreeId(), principal.getName());
    return testRuns.approve(testRunId, principal.getName());
  }

  @PostMapping("/test-runs/{testRunId}/reject")
  public TestRunView rejectTestRun(@PathVariable UUID testRunId, Principal principal) {
    access.requireWorktree(testRuns.get(testRunId).worktreeId(), principal.getName());
    return testRuns.reject(testRunId, principal.getName());
  }

  @PostMapping("/test-runs/{testRunId}/execute")
  public TestRunView executeTestRun(@PathVariable UUID testRunId, Principal principal) {
    access.requireWorktree(testRuns.get(testRunId).worktreeId(), principal.getName());
    return testRuns.execute(testRunId);
  }
}
