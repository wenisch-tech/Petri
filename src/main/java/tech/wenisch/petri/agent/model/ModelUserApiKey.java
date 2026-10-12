package tech.wenisch.petri.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A user's own API key for one provider instance. */
@Entity
@Table(name = "model_user_api_key")
class ModelUserApiKey {
  @Id private UUID id;

  @Column(name = "instance_id", nullable = false)
  private UUID instanceId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "api_key_ciphertext", nullable = false, columnDefinition = "TEXT")
  private String apiKeyCiphertext;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ModelUserApiKey() {}

  ModelUserApiKey(UUID id, UUID instanceId, UUID userId, String ciphertext, Instant now) {
    this.id = id;
    this.instanceId = instanceId;
    this.userId = userId;
    this.apiKeyCiphertext = ciphertext;
    this.updatedAt = now;
  }

  void replace(String ciphertext, Instant now) {
    this.apiKeyCiphertext = ciphertext;
    this.updatedAt = now;
  }

  UUID instanceId() { return instanceId; }
  UUID userId() { return userId; }
  String apiKeyCiphertext() { return apiKeyCiphertext; }
}
