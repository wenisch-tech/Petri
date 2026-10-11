package tech.wenisch.petri.agent.orchestration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_workflow")
class AgentWorkflow {
  @Id private UUID id;
  @Column(name = "chat_id", nullable = false) private UUID chatId;
  @Column(name = "chat_message_index", nullable = false) private int chatMessageIndex;
  @Column(name = "execution_worktree_id", nullable = false) private UUID executionWorktreeId;
  @Column(nullable = false, columnDefinition = "TEXT") private String prompt;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 48) private AgentWorkflowStatus status;
  @Enumerated(EnumType.STRING) @Column(name = "current_stage", nullable = false, length = 48)
  private AgentWorkflowStage currentStage;
  @Column(name = "baseline_head", length = 64) private String baselineHead;
  @Column(name = "baseline_tree", length = 64) private String baselineTree;
  @Column(name = "current_plan_revision") private Integer currentPlanRevision;
  @Column(name = "approved_plan_revision") private Integer approvedPlanRevision;
  @Column(name = "hold_reason", length = 2000) private String holdReason;
  @Column(name = "created_by", nullable = false) private UUID createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Column(name = "completed_at") private Instant completedAt;

  protected AgentWorkflow() {}

  AgentWorkflow(UUID id, UUID chatId, int messageIndex, UUID worktreeId, String prompt,
      UUID createdBy, Instant now) {
    this.id = id;
    this.chatId = chatId;
    this.chatMessageIndex = messageIndex;
    this.executionWorktreeId = worktreeId;
    this.prompt = prompt;
    this.status = AgentWorkflowStatus.PENDING;
    this.currentStage = AgentWorkflowStage.QUEUED;
    this.createdBy = createdBy;
    this.createdAt = now;
    this.updatedAt = now;
  }

  void baseline(String head, String tree, Instant now) {
    baselineHead = head;
    baselineTree = tree;
    updatedAt = now;
  }

  void transition(AgentWorkflowStage target, AgentWorkflowStatus nextStatus, String reason, Instant now) {
    if (terminal()) throw new IllegalStateException("Workflow is already terminal");
    currentStage = target;
    status = nextStatus;
    holdReason = reason;
    updatedAt = now;
    if (nextStatus == AgentWorkflowStatus.COMPLETED || nextStatus == AgentWorkflowStatus.CANCELLED) {
      completedAt = now;
    }
  }

  void currentPlan(int revision, Instant now) {
    currentPlanRevision = revision;
    approvedPlanRevision = null;
    updatedAt = now;
  }

  void approvePlan(int revision, Instant now) {
    if (currentPlanRevision == null || currentPlanRevision != revision) {
      throw new IllegalStateException("Only the current plan revision can be approved");
    }
    approvedPlanRevision = revision;
    updatedAt = now;
  }

  void invalidateApproval(Instant now) {
    approvedPlanRevision = null;
    updatedAt = now;
  }

  boolean terminal() {
    return status == AgentWorkflowStatus.COMPLETED || status == AgentWorkflowStatus.CANCELLED;
  }

  UUID id() { return id; }
  UUID chatId() { return chatId; }
  int chatMessageIndex() { return chatMessageIndex; }
  UUID executionWorktreeId() { return executionWorktreeId; }
  String prompt() { return prompt; }
  AgentWorkflowStatus status() { return status; }
  AgentWorkflowStage currentStage() { return currentStage; }
  String baselineHead() { return baselineHead; }
  String baselineTree() { return baselineTree; }
  Integer currentPlanRevision() { return currentPlanRevision; }
  Integer approvedPlanRevision() { return approvedPlanRevision; }
  String holdReason() { return holdReason; }
  UUID createdBy() { return createdBy; }
  Instant createdAt() { return createdAt; }
  Instant updatedAt() { return updatedAt; }
  Instant completedAt() { return completedAt; }
}
