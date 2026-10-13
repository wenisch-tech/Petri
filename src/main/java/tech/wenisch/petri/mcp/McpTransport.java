package tech.wenisch.petri.mcp;

public enum McpTransport {
  AUTO("Automatic"),
  STREAMABLE_HTTP("Streamable HTTP"),
  SSE("Legacy SSE");

  private final String label;

  McpTransport(String label) { this.label = label; }

  public String label() { return label; }
}
