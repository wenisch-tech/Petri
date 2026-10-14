package tech.wenisch.petri.agent.tool;

import java.util.Map;

final class ToolInputs {
  private ToolInputs() {}

  static String string(Map<String, Object> input, String name, String fallback, int maxLength) {
    Object raw = input.get(name);
    if (raw == null) return fallback;
    if (!(raw instanceof String value)) throw new IllegalArgumentException(name + " must be a string");
    if (value.length() > maxLength) throw new IllegalArgumentException(name + " is too long");
    return value;
  }

  static String requiredString(Map<String, Object> input, String name, int maxLength) {
    String value = string(input, name, null, maxLength);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value;
  }

  static int integer(Map<String, Object> input, String name, int fallback, int minimum, int maximum) {
    Object raw = input.get(name);
    if (raw == null) return fallback;
    // Several models send numbers as strings ("20"); a whole number in quotes is accepted.
    if (raw instanceof String text && text.strip().matches("-?\\d{1,9}")) {
      raw = Integer.valueOf(text.strip());
    }
    if (!(raw instanceof Number number)) throw new IllegalArgumentException(name + " must be a number");
    int value = number.intValue();
    if (value < minimum || value > maximum || number.doubleValue() != value) {
      throw new IllegalArgumentException(name + " is outside its allowed range");
    }
    return value;
  }
}
