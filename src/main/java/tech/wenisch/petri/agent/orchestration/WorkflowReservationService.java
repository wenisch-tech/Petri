package tech.wenisch.petri.agent.orchestration;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowReservationService {
  private final WorktreeExecutionReservationRepository reservations;
  private final AgentWorkflowRepository workflows;

  public WorkflowReservationService(
      WorktreeExecutionReservationRepository reservations, AgentWorkflowRepository workflows) {
    this.reservations = reservations;
    this.workflows = workflows;
  }

  /**
   * Blocks user Git and editor actions only while the reserving workflow is actually executing. A
   * workflow waiting for the user, on hold, or finished leaves the worktree free to edit.
   */
  @Transactional(readOnly = true)
  public void assertUserEditable(UUID worktreeId) {
    reservations.findById(worktreeId).ifPresent(value -> {
      AgentWorkflow workflow = workflows.findById(value.workflowId()).orElse(null);
      if (workflow == null) return;
      AgentWorkflowStatus status = workflow.status();
      if (status == AgentWorkflowStatus.RUNNING || status == AgentWorkflowStatus.PENDING
          || status == AgentWorkflowStatus.CANCELLING) {
        throw new IllegalStateException(
            "Worktree is in use by workflow " + value.workflowId() + " while it is " + status);
      }
    });
  }

  @Transactional(readOnly = true)
  public void assertUnreserved(UUID worktreeId) {
    reservations.findById(worktreeId).ifPresent(value -> {
      throw new IllegalStateException(
          "Worktree is reserved by workflow " + value.workflowId());
    });
  }

  @Transactional(readOnly = true)
  public UUID reservedBy(UUID worktreeId) {
    return reservations.findById(worktreeId).map(WorktreeExecutionReservation::workflowId).orElse(null);
  }
}
