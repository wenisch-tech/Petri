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
import tech.wenisch.petri.agent.model.ReasoningEffort;
import tech.wenisch.petri.agent.model.TextVerbosity;

@Entity
@Table(name = "orchestration_profile")
class OrchestrationProfile {
  @Id private UUID id;
  @Column(name = "project_id", nullable = false) private UUID projectId;
  @Column(name = "user_id", nullable = false) private UUID userId;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private AgentWorkflowRole role;
  @Enumerated(EnumType.STRING) @Column(name = "model_provider", nullable = false, length = 32)
  private ModelProvider provider;
  @Column(name = "provider_instance_id") private UUID providerInstanceId;
  @Column(name = "model_name", nullable = false, length = 200) private String model;
  @Column(name = "max_model_turns", nullable = false) private int maxModelTurns;
  @Column(name = "max_output_tokens", nullable = false) private int maxOutputTokens;
  @Column(name = "context_window_tokens", nullable = false) private int contextWindowTokens;
  @Enumerated(EnumType.STRING) @Column(name = "reasoning_effort", length = 16) private ReasoningEffort reasoningEffort;
  @Enumerated(EnumType.STRING) @Column(name = "text_verbosity", length = 16) private TextVerbosity textVerbosity;
  @Column(name = "max_attempts", nullable = false) private int maxAttempts;
  @Column(name = "prompt_addendum", columnDefinition = "TEXT") private String promptAddendum;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected OrchestrationProfile() {}
  OrchestrationProfile(UUID id, UUID projectId, UUID userId, AgentWorkflowRole role,
      ModelProvider provider, UUID providerInstanceId, String model, int turns, int outputTokens,
      int contextTokens, ReasoningEffort reasoning, TextVerbosity verbosity, int attempts,
      String addendum, Instant now) {
    this.id = id; this.projectId = projectId; this.userId = userId; this.role = role;
    replace(provider, providerInstanceId, model, turns, outputTokens, contextTokens, reasoning, verbosity, attempts, addendum, now);
    this.createdAt = now;
  }
  void replace(ModelProvider provider, UUID providerInstanceId, String model, int turns,
      int outputTokens, int contextTokens, ReasoningEffort reasoning, TextVerbosity verbosity,
      int attempts, String addendum, Instant now) {
    this.provider = provider; this.providerInstanceId = providerInstanceId; this.model = model; this.maxModelTurns = turns;
    this.maxOutputTokens = outputTokens; this.contextWindowTokens = contextTokens;
    this.reasoningEffort = reasoning; this.textVerbosity = verbosity; this.maxAttempts = attempts;
    this.promptAddendum = addendum; this.updatedAt = now;
  }
  UUID id() { return id; } UUID projectId() { return projectId; } UUID userId() { return userId; }
  AgentWorkflowRole role() { return role; } ModelProvider provider() { return provider; }
  UUID providerInstanceId() { return providerInstanceId; }
  String model() { return model; } int maxModelTurns() { return maxModelTurns; }
  int maxOutputTokens() { return maxOutputTokens; } int contextWindowTokens() { return contextWindowTokens; }
  ReasoningEffort reasoningEffort() { return reasoningEffort; } TextVerbosity textVerbosity() { return textVerbosity; }
  int maxAttempts() { return maxAttempts; } String promptAddendum() { return promptAddendum; }
}
