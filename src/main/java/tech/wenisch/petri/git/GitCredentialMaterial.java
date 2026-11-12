package tech.wenisch.petri.git;

/** In-memory credential material. Deliberately without a toString method. */
public final class GitCredentialMaterial {
  private final GitAuthenticationType authenticationType;
  private final String username;
  private final String secret;

  public GitCredentialMaterial(
      GitAuthenticationType authenticationType, String username, String secret) {
    this.authenticationType = authenticationType;
    this.username = username;
    this.secret = secret;
  }

  public GitAuthenticationType authenticationType() { return authenticationType; }
  public String username() { return username; }
  public String secret() { return secret; }
}
