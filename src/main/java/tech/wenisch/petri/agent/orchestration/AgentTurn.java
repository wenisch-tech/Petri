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
@Table(name = "agent_turn")
class AgentTurn {
  @Id private UUID id;

  @Column(name = "run_id", nullable = false)
  private UUID runId;

  @Column(name = "turn_index", nullable = false)
  private int index;

  @Enumerated(EnumType.STRING)
  @Column(name = "turn_kind", nullable = false, length = 32)
  private AgentTurnKind kind;

  @Column(name = "provider_response_id", length = 200)
  private String providerResponseId;

  @Column(name = "tool_call_id", length = 200)
  private String toolCallId;

  @Column(name = "tool_name", length = 80)
  private String toolName;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected AgentTurn() {}

  AgentTurn(
      UUID id,
      UUID runId,
      int index,
      AgentTurnKind kind,
      String providerResponseId,
      String toolCallId,
      String toolName,
      String content,
      Instant createdAt) {
    this.id = id;
    this.runId = runId;
    this.index = index;
    this.kind = kind;
    this.providerResponseId = providerResponseId;
    this.toolCallId = toolCallId;
    this.toolName = toolName;
    this.content = content;
    this.createdAt = createdAt;
  }

  UUID id() { return id; }
  int index() { return index; }
  AgentTurnKind kind() { return kind; }
  String providerResponseId() { return providerResponseId; }
  String toolCallId() { return toolCallId; }
  String toolName() { return toolName; }
  String content() { return content; }
  Instant createdAt() { return createdAt; }
}
