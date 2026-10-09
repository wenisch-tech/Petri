package tech.wenisch.petri.agent.model;

import java.util.List;

public record ModelConversationMessage(
    ModelMessageRole role,
    String content,
    List<ModelToolCall> toolCalls,
    String toolCallId) {

  public ModelConversationMessage {
    if (role == null) throw new IllegalArgumentException("Message role is required");
    content = content == null ? "" : content;
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  public static ModelConversationMessage user(String content) {
    return new ModelConversationMessage(ModelMessageRole.USER, content, List.of(), null);
  }

  public static ModelConversationMessage assistant(
      String content, List<ModelToolCall> toolCalls) {
    return new ModelConversationMessage(ModelMessageRole.ASSISTANT, content, toolCalls, null);
  }

  public static ModelConversationMessage tool(String callId, String content) {
    return new ModelConversationMessage(ModelMessageRole.TOOL, content, List.of(), callId);
  }
}
