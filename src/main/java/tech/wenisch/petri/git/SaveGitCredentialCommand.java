package tech.wenisch.petri.git;

/** Deliberately has no generated toString implementation that could expose the secret. */
public final class SaveGitCredentialCommand {
  private final GitAuthenticationType authenticationType;
  private final String username;
  private final String secret;

  public SaveGitCredentialCommand(
      GitAuthenticationType authenticationType, String username, String secret) {
    this.authenticationType = authenticationType;
    this.username = username;
    this.secret = secret;
  }

  public GitAuthenticationType authenticationType() { return authenticationType; }
  public String username() { return username; }
  public String secret() { return secret; }
}
