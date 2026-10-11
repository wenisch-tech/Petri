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
@Table(name = "agent_workflow_artifact")
class AgentWorkflowArtifact {
  @Id private UUID id;
  @Column(name = "workflow_id", nullable = false) private UUID workflowId;
  @Column(name = "agent_run_id") private UUID agentRunId;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 48) private WorkflowArtifactKind kind;
  @Column(nullable = false) private int revision;
  @Column(name = "content_json", nullable = false, columnDefinition = "TEXT") private String contentJson;
  @Column(name = "display_markdown", columnDefinition = "TEXT") private String displayMarkdown;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected AgentWorkflowArtifact() {}

  AgentWorkflowArtifact(UUID id, UUID workflowId, UUID agentRunId, WorkflowArtifactKind kind,
      int revision, String contentJson, String displayMarkdown, Instant createdAt) {
    this.id = id;
    this.workflowId = workflowId;
    this.agentRunId = agentRunId;
    this.kind = kind;
    this.revision = revision;
    this.contentJson = contentJson;
    this.displayMarkdown = displayMarkdown;
    this.createdAt = createdAt;
  }

  UUID id() { return id; }
  UUID workflowId() { return workflowId; }
  UUID agentRunId() { return agentRunId; }
  WorkflowArtifactKind kind() { return kind; }
  int revision() { return revision; }
  String contentJson() { return contentJson; }
  String displayMarkdown() { return displayMarkdown; }
  Instant createdAt() { return createdAt; }
}
