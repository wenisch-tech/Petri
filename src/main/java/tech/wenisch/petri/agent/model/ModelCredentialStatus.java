package tech.wenisch.petri.agent.model;

import java.time.Instant;

public record ModelCredentialStatus(
    ModelProvider provider,
    boolean configured,
    boolean hasApiKey,
    String baseUrl,
    String model,
    Integer maxOutputTokens,
    ReasoningEffort reasoningEffort,
    TextVerbosity textVerbosity,
    Instant updatedAt) {
  static ModelCredentialStatus missing(ModelProvider provider) {
    return new ModelCredentialStatus(
        provider, false, false, provider.defaultBaseUrl(), null, null, null, null, null);
  }
}
