package tech.wenisch.petri.workspace;

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
@Table(name = "workspace_project_preference")
class WorkspaceModelPreference {
  @Id private UUID id;
  @Column(name = "project_id", nullable = false) private UUID projectId;
  @Column(name = "user_id", nullable = false) private UUID userId;
  @Enumerated(EnumType.STRING)
  @Column(name = "model_provider", nullable = false, length = 32) private ModelProvider provider;
  @Column(name = "provider_instance_id") private UUID providerInstanceId;
  @Column(name = "model_name", nullable = false, length = 200) private String model;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected WorkspaceModelPreference() {}

  WorkspaceModelPreference(
      UUID id, UUID projectId, UUID userId, ModelProvider provider, UUID providerInstanceId,
      String model, Instant now) {
    this.id = id;
    this.projectId = projectId;
    this.userId = userId;
    this.provider = provider;
    this.providerInstanceId = providerInstanceId;
    this.model = model;
    this.createdAt = now;
    this.updatedAt = now;
  }

  void replace(ModelProvider value, UUID instanceId, String model, Instant now) {
    provider = value;
    providerInstanceId = instanceId;
    this.model = model;
    updatedAt = now;
  }

  ModelProvider provider() { return provider; }
  UUID providerInstanceId() { return providerInstanceId; }
  String model() { return model; }
}
