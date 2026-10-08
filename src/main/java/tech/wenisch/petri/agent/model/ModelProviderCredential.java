package tech.wenisch.petri.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "model_provider_credential")
class ModelProviderCredential {
  @Id
  @Enumerated(EnumType.STRING)
  @Column(length = 32)
  private ModelProvider provider;

  @Column(name = "api_key_ciphertext", nullable = false, columnDefinition = "TEXT")
  private String apiKeyCiphertext;

  @Column(name = "model_name", nullable = false, length = 200)
  private String model;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_by", nullable = false)
  private UUID updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ModelProviderCredential() {}

  ModelProviderCredential(
      ModelProvider provider,
      String apiKeyCiphertext,
      String model,
      UUID actorId,
      Instant now) {
    this.provider = provider;
    this.apiKeyCiphertext = apiKeyCiphertext;
    this.model = model;
    this.createdBy = actorId;
    this.createdAt = now;
    this.updatedBy = actorId;
    this.updatedAt = now;
  }

  void replace(String apiKeyCiphertext, String model, UUID actorId, Instant now) {
    this.apiKeyCiphertext = apiKeyCiphertext;
    this.model = model;
    this.updatedBy = actorId;
    this.updatedAt = now;
  }

  ModelProvider provider() { return provider; }
  String apiKeyCiphertext() { return apiKeyCiphertext; }
  String model() { return model; }
  Instant updatedAt() { return updatedAt; }
}
