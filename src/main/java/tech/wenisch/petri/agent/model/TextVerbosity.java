package tech.wenisch.petri.agent.model;

import java.util.Locale;

public enum TextVerbosity {
  LOW,
  MEDIUM,
  HIGH;

  public String apiValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
