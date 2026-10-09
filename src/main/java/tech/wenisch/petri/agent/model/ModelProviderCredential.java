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

  @Column(name = "api_key_ciphertext", columnDefinition = "TEXT")
  private String apiKeyCiphertext;

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
      String baseUrl,
      String model,
      int maxOutputTokens,
      int contextWindowTokens,
      ReasoningEffort reasoningEffort,
      TextVerbosity textVerbosity,
      UUID actorId,
      Instant now) {
    this.provider = provider;
    this.apiKeyCiphertext = apiKeyCiphertext;
    this.baseUrl = baseUrl;
    this.model = model;
    this.maxOutputTokens = maxOutputTokens;
    this.contextWindowTokens = contextWindowTokens;
    this.reasoningEffort = reasoningEffort;
    this.textVerbosity = textVerbosity;
    this.createdBy = actorId;
    this.createdAt = now;
    this.updatedBy = actorId;
    this.updatedAt = now;
  }

  void replace(
      String apiKeyCiphertext,
      String baseUrl,
      String model,
      int maxOutputTokens,
      int contextWindowTokens,
      ReasoningEffort reasoningEffort,
      TextVerbosity textVerbosity,
      UUID actorId,
      Instant now) {
    this.apiKeyCiphertext = apiKeyCiphertext;
    this.baseUrl = baseUrl;
    this.model = model;
    this.maxOutputTokens = maxOutputTokens;
    this.contextWindowTokens = contextWindowTokens;
    this.reasoningEffort = reasoningEffort;
    this.textVerbosity = textVerbosity;
    this.updatedBy = actorId;
    this.updatedAt = now;
  }

  ModelProvider provider() { return provider; }
  String apiKeyCiphertext() { return apiKeyCiphertext; }
  String baseUrl() { return baseUrl; }
  String model() { return model; }
  int maxOutputTokens() { return maxOutputTokens; }
  int contextWindowTokens() { return contextWindowTokens; }
  ReasoningEffort reasoningEffort() { return reasoningEffort; }
  TextVerbosity textVerbosity() { return textVerbosity; }
  Instant updatedAt() { return updatedAt; }
}
