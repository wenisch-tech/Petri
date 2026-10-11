package tech.wenisch.petri.agent.orchestration;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.agent.change.ChangeProposalService;
import tech.wenisch.petri.agent.change.ChangeProposalStatus;
import tech.wenisch.petri.agent.run.TestRunService;
import tech.wenisch.petri.agent.run.TestRunStatus;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

/** Executes only actions covered by an explicit, revision-bound workflow plan approval. */
@Service
class WorkflowConsentAutomationService {
  private static final Logger log = LoggerFactory.getLogger(WorkflowConsentAutomationService.class);
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

  // REQUIRES_NEW: an after-commit listener otherwise joins the finished transaction and loses its writes.
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void requested(AgentActionRequestedEvent event) {
    AgentRunView run = runs.get(event.agentRunId());
    if (run.workflowId() == null || run.workflowRole() != AgentWorkflowRole.IMPLEMENTER) return;
    AgentWorkflow workflow = ledger.require(run.workflowId());
    if (workflow.approvedPlanRevision() == null || workflow.terminal()) return;
    AppUser creator = users.findById(workflow.createdBy())
        .orElseThrow(() -> new IllegalStateException("Workflow creator no longer exists"));
    if (event.kind() == AgentActionRequestedEvent.AgentActionKind.CHANGE_PROPOSAL) {
      changes.approveAndApply(event.actionId(), creator.getEmail());
    } else if (event.kind() == AgentActionRequestedEvent.AgentActionKind.TEST_RUN) {
      var test = tests.get(event.actionId(), workflow.executionWorktreeId());
      if (test.status() == TestRunStatus.PROPOSED) tests.approve(test.id(), creator.getEmail());
      tests.execute(test.id());
    }
  }

  /**
   * Applies every action the approved plan already covers but that is still open, for example after
   * an after-commit listener failed. Idempotent; failures are left for the evidence gate to judge.
   */
  void settleOpenActions(UUID workflowId) {
    AgentWorkflow workflow = ledger.require(workflowId);
    if (workflow.approvedPlanRevision() == null || workflow.terminal()) return;
    AppUser creator = users.findById(workflow.createdBy()).orElse(null);
    if (creator == null) return;
    for (AgentRunView run : runs.workflowRuns(workflowId)) {
      if (run.workflowRole() != AgentWorkflowRole.IMPLEMENTER) continue;
      for (var proposal : changes.listForAgentRun(run.id())) {
        if (proposal.status() == ChangeProposalStatus.CONFLICT) {
          try { changes.recoverIfApplied(proposal.id()); }
          catch (RuntimeException exception) {
            log.warn("Could not recover workflow change {}", proposal.id(), exception);
          }
          continue;
        }
        if (proposal.status() != ChangeProposalStatus.PROPOSED
            && proposal.status() != ChangeProposalStatus.APPROVED) continue;
        try { changes.approveAndApply(proposal.id(), creator.getEmail()); }
        catch (RuntimeException exception) {
          log.warn("Could not apply approved workflow change {}", proposal.id(), exception);
        }
      }
      for (var test : tests.listForAgentRun(run.id())) {
        if (test.status() != TestRunStatus.PROPOSED && test.status() != TestRunStatus.APPROVED) continue;
        try {
          if (test.status() == TestRunStatus.PROPOSED) tests.approve(test.id(), creator.getEmail());
          tests.execute(test.id());
        } catch (RuntimeException exception) {
          log.warn("Could not run approved workflow test {}", test.id(), exception);
        }
      }
    }
  }
}
