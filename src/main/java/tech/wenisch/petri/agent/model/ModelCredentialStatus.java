package tech.wenisch.petri.agent.model;

import java.time.Instant;

public record ModelCredentialStatus(
    ModelProvider provider,
    boolean configured,
    String model,
    Instant updatedAt) {
  static ModelCredentialStatus missing(ModelProvider provider) {
    return new ModelCredentialStatus(provider, false, null, null);
  }
}
