package tech.wenisch.petri.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One model ID offered by a provider instance, either discovered or typed in by a person. */
@Entity
@Table(name = "model_provider_model")
class ModelProviderModel {
  @Id private UUID id;

  @Column(name = "instance_id", nullable = false)
  private UUID instanceId;

  @Column(name = "model_id", nullable = false, length = 200)
  private String modelId;

  @Column(nullable = false)
  private boolean custom;

  @Column(name = "owner_id")
  private UUID ownerId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected ModelProviderModel() {}

  ModelProviderModel(
      UUID id, UUID instanceId, String modelId, boolean custom, UUID ownerId, Instant createdAt) {
    this.id = id;
    this.instanceId = instanceId;
    this.modelId = modelId;
    this.custom = custom;
    this.ownerId = ownerId;
    this.createdAt = createdAt;
  }

  UUID id() { return id; }
  UUID instanceId() { return instanceId; }
  String modelId() { return modelId; }
  boolean custom() { return custom; }
  UUID ownerId() { return ownerId; }
}
