package tech.wenisch.petri.agent.model;

public record ModelToolOutput(String callId, String output) {
  public ModelToolOutput {
    if (callId == null || callId.isBlank()) throw new IllegalArgumentException("Call ID is required");
    if (output == null) throw new IllegalArgumentException("Tool output is required");
  }
}
