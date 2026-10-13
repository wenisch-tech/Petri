package tech.wenisch.petri.mcp;

public enum McpServerStatus {
  /** Not contacted yet, or the last contact worked. */
  ACTIVE,
  /** The server asked for credentials Petri does not have (or they expired). */
  NEEDS_AUTH,
  ERROR
}
