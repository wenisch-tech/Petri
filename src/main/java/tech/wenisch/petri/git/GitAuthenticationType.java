package tech.wenisch.petri.git;

public enum GitAuthenticationType {
  TOKEN("Access token"),
  USERNAME_PASSWORD("Username and password");

  private final String label;

  GitAuthenticationType(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }
}
