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
@Table(name = "agent_workflow_transition")
class AgentWorkflowTransition {
  @Id private UUID id;
  @Column(name = "workflow_id", nullable = false) private UUID workflowId;
  @Enumerated(EnumType.STRING) @Column(name = "from_stage", length = 48) private AgentWorkflowStage fromStage;
  @Enumerated(EnumType.STRING) @Column(name = "to_stage", nullable = false, length = 48) private AgentWorkflowStage toStage;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private WorkflowGateOutcome outcome;
  @Column(nullable = false, length = 320) private String actor;
  @Column(length = 2000) private String reason;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected AgentWorkflowTransition() {}
  AgentWorkflowTransition(UUID id, UUID workflowId, AgentWorkflowStage fromStage,
      AgentWorkflowStage toStage, WorkflowGateOutcome outcome, String actor, String reason,
      Instant createdAt) {
    this.id = id; this.workflowId = workflowId; this.fromStage = fromStage; this.toStage = toStage;
    this.outcome = outcome; this.actor = actor; this.reason = reason; this.createdAt = createdAt;
  }
  UUID id() { return id; }
  UUID workflowId() { return workflowId; }
  AgentWorkflowStage fromStage() { return fromStage; }
  AgentWorkflowStage toStage() { return toStage; }
  WorkflowGateOutcome outcome() { return outcome; }
  String actor() { return actor; }
  String reason() { return reason; }
  Instant createdAt() { return createdAt; }
}
