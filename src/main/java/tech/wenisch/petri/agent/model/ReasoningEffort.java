package tech.wenisch.petri.agent.model;

import java.util.Locale;

public enum ReasoningEffort {
  NONE,
  MINIMAL,
  LOW,
  MEDIUM,
  HIGH,
  XHIGH,
  MAX;

  public String apiValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
