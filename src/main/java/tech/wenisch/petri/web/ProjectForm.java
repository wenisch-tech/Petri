package tech.wenisch.petri.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ProjectForm {
  @NotBlank
  @Size(max = 120)
  private String name;

  @NotBlank
  @Size(max = 2048)
  private String repositoryUri;

  @Size(max = 255)
  private String defaultBranch = "main";

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getRepositoryUri() { return repositoryUri; }
  public void setRepositoryUri(String repositoryUri) { this.repositoryUri = repositoryUri; }
  public String getDefaultBranch() { return defaultBranch; }
  public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }
}
