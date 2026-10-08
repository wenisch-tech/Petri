package tech.wenisch.petri.agent.tool;

import java.util.Map;

public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {}
