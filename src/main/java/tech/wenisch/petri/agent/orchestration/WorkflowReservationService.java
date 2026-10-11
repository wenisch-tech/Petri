package tech.wenisch.petri.agent.orchestration;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowReservationService {
  private final WorktreeExecutionReservationRepository reservations;
  public WorkflowReservationService(WorktreeExecutionReservationRepository reservations) {
    this.reservations = reservations;
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
