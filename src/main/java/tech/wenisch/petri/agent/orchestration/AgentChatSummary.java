package tech.wenisch.petri.agent.orchestration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import tech.wenisch.petri.agent.model.ModelProvider;

@Entity
@Table(name = "agent_chat_summary")
class AgentChatSummary {
  @Id private UUID id;
  @Column(name = "chat_id", nullable = false) private UUID chatId;
  @Column(name = "from_message_index", nullable = false) private int fromMessageIndex;
  @Column(name = "through_message_index", nullable = false) private int throughMessageIndex;
  @Column(nullable = false, columnDefinition = "TEXT") private String content;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private ModelProvider provider;
  @Column(name = "model_name", nullable = false, length = 200) private String model;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected AgentChatSummary() {}

  AgentChatSummary(UUID id, UUID chatId, int fromMessageIndex, int throughMessageIndex,
      String content, ModelProvider provider, String model, Instant createdAt) {
    this.id = id;
    this.chatId = chatId;
    this.fromMessageIndex = fromMessageIndex;
    this.throughMessageIndex = throughMessageIndex;
    this.content = content;
    this.provider = provider;
    this.model = model;
    this.createdAt = createdAt;
  }

  UUID id() { return id; }
  int fromMessageIndex() { return fromMessageIndex; }
  int throughMessageIndex() { return throughMessageIndex; }
  String content() { return content; }
  ModelProvider provider() { return provider; }
  String model() { return model; }
  Instant createdAt() { return createdAt; }
}
