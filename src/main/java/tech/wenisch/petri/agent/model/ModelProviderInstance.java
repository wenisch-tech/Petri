package tech.wenisch.petri.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A configured model endpoint. The kind ({@link ModelProvider}) selects the client implementation;
 * several instances of the same kind may exist. A null owner marks a global instance that an
 * administrator manages for every user, otherwise the instance is private to its owner.
 */
@Entity
@Table(name = "model_provider_instance")
class ModelProviderInstance {
  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "provider", nullable = false, length = 32)
  private ModelProvider kind;

  @Column(name = "display_name", nullable = false, length = 120)
  private String displayName;

  @Column(name = "base_url", length = 2048)
  private String baseUrl;

  @Column(name = "model_name", nullable = false, length = 200)
  private String model;

  @Column(name = "max_output_tokens", nullable = false)
  private int maxOutputTokens;

  @Column(name = "context_window_tokens", nullable = false)
  private int contextWindowTokens;

  @Enumerated(EnumType.STRING)
  @Column(name = "reasoning_effort", length = 16)
  private ReasoningEffort reasoningEffort;

  @Enumerated(EnumType.STRING)
  @Column(name = "text_verbosity", length = 16)
  private TextVerbosity textVerbosity;

  @Column(name = "shared_api_key_ciphertext", columnDefinition = "TEXT")
  private String sharedApiKeyCiphertext;

  @Column(name = "owner_id")
  private UUID ownerId;

  @Column(name = "models_refreshed_at")
  private Instant modelsRefreshedAt;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_by", nullable = false)
  private UUID updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ModelProviderInstance() {}

  ModelProviderInstance(UUID id, ModelProvider kind, UUID ownerId, UUID actorId, Instant now) {
    this.id = id;
    this.kind = kind;
    this.ownerId = ownerId;
    this.createdBy = actorId;
    this.createdAt = now;
    this.updatedBy = actorId;
    this.updatedAt = now;
  }

  void configure(
      String displayName,
      String baseUrl,
      String model,
      int maxOutputTokens,
      int contextWindowTokens,
      ReasoningEffort reasoningEffort,
      TextVerbosity textVerbosity,
      boolean enabled,
      UUID actorId,
      Instant now) {
    this.displayName = displayName;
    this.baseUrl = baseUrl;
    this.model = model;
    this.maxOutputTokens = maxOutputTokens;
    this.contextWindowTokens = contextWindowTokens;
    this.reasoningEffort = reasoningEffort;
    this.textVerbosity = textVerbosity;
    this.enabled = enabled;
    this.updatedBy = actorId;
    this.updatedAt = now;
  }

  void replaceSharedKey(String ciphertext) { this.sharedApiKeyCiphertext = ciphertext; }

  void markModelsRefreshed(Instant now) { this.modelsRefreshedAt = now; }

  UUID id() { return id; }
  ModelProvider kind() { return kind; }
  String displayName() { return displayName; }
  String baseUrl() { return baseUrl; }
  String model() { return model; }
  int maxOutputTokens() { return maxOutputTokens; }
  int contextWindowTokens() { return contextWindowTokens; }
  ReasoningEffort reasoningEffort() { return reasoningEffort; }
  TextVerbosity textVerbosity() { return textVerbosity; }
  String sharedApiKeyCiphertext() { return sharedApiKeyCiphertext; }
  UUID ownerId() { return ownerId; }
  boolean global() { return ownerId == null; }
  boolean enabled() { return enabled; }
  Instant modelsRefreshedAt() { return modelsRefreshedAt; }
  Instant createdAt() { return createdAt; }
  Instant updatedAt() { return updatedAt; }
}
