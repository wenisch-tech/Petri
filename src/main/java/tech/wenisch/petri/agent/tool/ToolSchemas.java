package tech.wenisch.petri.agent.tool;

import java.util.List;
import java.util.Map;

final class ToolSchemas {
  private ToolSchemas() {}

  static Map<String, Object> object(
      Map<String, Object> properties, List<String> required) {
    return Map.of(
        "type", "object",
        "properties", properties,
        "required", required,
        "additionalProperties", false);
  }

  static Map<String, Object> string(String description) {
    return Map.of("type", "string", "description", description);
  }

  static Map<String, Object> nullableString(String description) {
    return Map.of("type", List.of("string", "null"), "description", description);
  }

  static Map<String, Object> integer(String description, int minimum, int maximum) {
    return Map.of(
        "type", "integer",
        "description", description,
        "minimum", minimum,
        "maximum", maximum);
  }

  static Map<String, Object> nullableInteger(
      String description, int minimum, int maximum) {
    return Map.of(
        "type", List.of("integer", "null"),
        "description", description,
        "minimum", minimum,
        "maximum", maximum);
  }
}
