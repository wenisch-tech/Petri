package tech.wenisch.petri.agent.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_run_event")
class AgentRunEvent {
  @Id private UUID id;

  @Column(name = "worktree_id", nullable = false)
  private UUID worktreeId;

  @Column(name = "agent_run_id")
  private UUID agentRunId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 32)
  private AgentRunEventType eventType;

  @Column(name = "tool_name", length = 80)
  private String toolName;

  @Column(nullable = false, length = 1000)
  private String summary;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  protected AgentRunEvent() {}

  AgentRunEvent(
      UUID id,
      UUID worktreeId,
      UUID agentRunId,
      AgentRunEventType eventType,
      String toolName,
      String summary,
      Instant occurredAt) {
    this.id = id;
    this.worktreeId = worktreeId;
    this.agentRunId = agentRunId;
    this.eventType = eventType;
    this.toolName = toolName;
    this.summary = summary;
    this.occurredAt = occurredAt;
  }

  UUID id() { return id; }
  UUID worktreeId() { return worktreeId; }
  UUID agentRunId() { return agentRunId; }
  AgentRunEventType eventType() { return eventType; }
  String toolName() { return toolName; }
  String summary() { return summary; }
  Instant occurredAt() { return occurredAt; }
}
