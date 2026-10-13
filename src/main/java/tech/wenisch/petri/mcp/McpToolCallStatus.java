package tech.wenisch.petri.mcp;

public enum McpToolCallStatus {
  PROPOSED,
  APPROVED,
  RUNNING,
  COMPLETED,
  REJECTED,
  FAILED;

  public boolean open() {
    return this == PROPOSED || this == APPROVED || this == RUNNING;
  }
}
