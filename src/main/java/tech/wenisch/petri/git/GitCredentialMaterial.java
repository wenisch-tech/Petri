package tech.wenisch.petri.git;

/** In-memory credential material. Deliberately package-private and without a toString method. */
final class GitCredentialMaterial {
  private final GitAuthenticationType authenticationType;
  private final String username;
  private final String secret;

  GitCredentialMaterial(
      GitAuthenticationType authenticationType, String username, String secret) {
    this.authenticationType = authenticationType;
    this.username = username;
    this.secret = secret;
  }

  GitAuthenticationType authenticationType() { return authenticationType; }
  String username() { return username; }
  String secret() { return secret; }
}
