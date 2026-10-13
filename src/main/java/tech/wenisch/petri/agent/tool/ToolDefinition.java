package tech.wenisch.petri.agent.tool;

import java.util.Map;

/**
 * A tool as the model sees it. {@code strict} asks the provider to enforce the schema exactly, which
 * Petri's own tools are written for; tools whose schema comes from elsewhere (MCP) turn it off.
 */
public record ToolDefinition(
    String name, String description, Map<String, Object> inputSchema, boolean strict) {
  public ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
    this(name, description, inputSchema, true);
  }
}
