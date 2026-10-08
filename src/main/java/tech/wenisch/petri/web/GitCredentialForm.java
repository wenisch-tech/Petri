package tech.wenisch.petri.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tech.wenisch.petri.git.GitAuthenticationType;

public class GitCredentialForm {
  @NotNull private GitAuthenticationType authenticationType = GitAuthenticationType.TOKEN;

  @Size(max = 320)
  private String username;

  @NotBlank
  @Size(max = 12000)
  private String secret;

  public GitAuthenticationType getAuthenticationType() { return authenticationType; }
  public void setAuthenticationType(GitAuthenticationType authenticationType) {
    this.authenticationType = authenticationType;
  }
  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }
  public String getSecret() { return secret; }
  public void setSecret(String secret) { this.secret = secret; }
}
