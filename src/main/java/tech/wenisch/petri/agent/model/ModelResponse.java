package tech.wenisch.petri.agent.model;

import java.util.List;

public record ModelResponse(
    String responseId,
    String status,
    String text,
    List<ModelToolCall> toolCalls,
    int inputTokens,
    int outputTokens) {
  public ModelResponse {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }
}
