package tech.wenisch.petri.agent.orchestration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_chat")
class AgentChat {
  @Id private UUID id;

  @Column(name = "worktree_id", nullable = false)
  private UUID worktreeId;

  @Column(nullable = false, length = 160)
  private String title;

  @Column(name = "queue_paused", nullable = false)
  private boolean queuePaused;

  @Column(name = "approval_gate_overridden", nullable = false)
  private boolean approvalGateOverridden;

  @Column(name = "auto_approve_changes", nullable = false)
  private boolean autoApproveChanges;

  @Column(name = "auto_approve_tests", nullable = false)
  private boolean autoApproveTests;

  @Column(name = "next_message_index", nullable = false)
  private int nextMessageIndex;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected AgentChat() {}

  AgentChat(UUID id, UUID worktreeId, String title, UUID createdBy, Instant now) {
    this.id = id;
    this.worktreeId = worktreeId;
    this.title = title;
    this.createdBy = createdBy;
    this.createdAt = now;
    this.updatedAt = now;
  }

  int nextMessageIndex(Instant now) {
    updatedAt = now;
    return nextMessageIndex++;
  }

  void pauseQueue(Instant now) {
    queuePaused = true;
    approvalGateOverridden = false;
    updatedAt = now;
  }

  void resumeQueue(boolean overrideApprovalGate, Instant now) {
    queuePaused = false;
    approvalGateOverridden = overrideApprovalGate;
    updatedAt = now;
  }

  void consumeApprovalOverride(Instant now) {
    approvalGateOverridden = false;
    updatedAt = now;
  }

  void setAutomation(boolean changes, boolean tests, Instant now) {
    autoApproveChanges = changes;
    autoApproveTests = tests;
    updatedAt = now;
  }

  UUID id() { return id; }
  UUID worktreeId() { return worktreeId; }
  String title() { return title; }
  boolean queuePaused() { return queuePaused; }
  boolean approvalGateOverridden() { return approvalGateOverridden; }
  boolean autoApproveChanges() { return autoApproveChanges; }
  boolean autoApproveTests() { return autoApproveTests; }
  UUID createdBy() { return createdBy; }
  Instant createdAt() { return createdAt; }
  Instant updatedAt() { return updatedAt; }
}
