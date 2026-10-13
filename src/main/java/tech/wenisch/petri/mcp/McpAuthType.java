package tech.wenisch.petri.mcp;

public enum McpAuthType {
  NONE("No authentication"),
  BEARER("Bearer token / API key"),
  HEADERS("Custom headers"),
  OAUTH("OAuth sign-in");

  private final String label;

  McpAuthType(String label) { this.label = label; }

  public String label() { return label; }
}
