package tech.wenisch.petri.git;

import java.time.Instant;

public record GitCredentialStatus(
    boolean configured,
    GitAuthenticationType authenticationType,
    String username,
    Instant updatedAt) {

  public static GitCredentialStatus notConfigured() {
    return new GitCredentialStatus(false, null, null, null);
  }
}
