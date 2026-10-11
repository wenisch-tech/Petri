package tech.wenisch.petri.agent.orchestration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "worktree_execution_reservation")
class WorktreeExecutionReservation {
  @Id @Column(name = "worktree_id") private UUID worktreeId;
  @Column(name = "workflow_id", nullable = false) private UUID workflowId;
  @Column(name = "reserved_by", nullable = false) private UUID reservedBy;
  @Column(name = "reserved_at", nullable = false) private Instant reservedAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  protected WorktreeExecutionReservation() {}
  WorktreeExecutionReservation(UUID worktreeId, UUID workflowId, UUID reservedBy, Instant now) {
    this.worktreeId = worktreeId; this.workflowId = workflowId; this.reservedBy = reservedBy;
    this.reservedAt = now; this.updatedAt = now;
  }
  UUID worktreeId() { return worktreeId; } UUID workflowId() { return workflowId; }
}
