package tech.wenisch.petri.agent.orchestration;

import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.agent.change.ChangeProposalService;
import tech.wenisch.petri.agent.run.TestRunService;
import tech.wenisch.petri.agent.run.TestRunStatus;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

/** Executes only actions covered by an explicit, revision-bound workflow plan approval. */
@Service
class WorkflowConsentAutomationService {
  private final AgentRunService runs;
  private final AgentWorkflowLedger ledger;
  private final ChangeProposalService changes;
  private final TestRunService tests;
  private final AppUserRepository users;

  WorkflowConsentAutomationService(
      AgentRunService runs, AgentWorkflowLedger ledger, ChangeProposalService changes,
      TestRunService tests, AppUserRepository users) {
    this.runs = runs; this.ledger = ledger; this.changes = changes; this.tests = tests; this.users = users;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void requested(AgentActionRequestedEvent event) {
    AgentRunView run = runs.get(event.agentRunId());
    if (run.workflowId() == null || run.workflowRole() != AgentWorkflowRole.IMPLEMENTER) return;
    AgentWorkflow workflow = ledger.require(run.workflowId());
    if (workflow.approvedPlanRevision() == null || workflow.terminal()) return;
    AppUser creator = users.findById(workflow.createdBy())
        .orElseThrow(() -> new IllegalStateException("Workflow creator no longer exists"));
    if (event.kind() == AgentActionRequestedEvent.AgentActionKind.CHANGE_PROPOSAL) {
      changes.approveAndApply(event.actionId(), creator.getEmail());
    } else {
      var test = tests.get(event.actionId(), workflow.executionWorktreeId());
      if (test.status() == TestRunStatus.PROPOSED) tests.approve(test.id(), creator.getEmail());
      tests.execute(test.id());
    }
  }
}
