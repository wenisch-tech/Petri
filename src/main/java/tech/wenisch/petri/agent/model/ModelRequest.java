package tech.wenisch.petri.agent.model;

import java.util.List;
import tech.wenisch.petri.agent.tool.ToolDefinition;

public record ModelRequest(
    String model,
    String instructions,
    String prompt,
    String previousResponseId,
    List<ModelToolOutput> toolOutputs,
    List<ToolDefinition> tools,
    int maxOutputTokens,
    ReasoningEffort reasoningEffort,
    TextVerbosity textVerbosity,
    List<ModelConversationMessage> conversation) {

  public ModelRequest(
      String model,
      String instructions,
      String prompt,
      String previousResponseId,
      List<ModelToolOutput> toolOutputs,
      List<ToolDefinition> tools,
      int maxOutputTokens,
      ReasoningEffort reasoningEffort,
      TextVerbosity textVerbosity) {
    this(
        model, instructions, prompt, previousResponseId, toolOutputs, tools, maxOutputTokens,
        reasoningEffort, textVerbosity, List.of());
  }

  public ModelRequest(
      String model,
      String instructions,
      String prompt,
      String previousResponseId,
      List<ModelToolOutput> toolOutputs,
      List<ToolDefinition> tools,
      int maxOutputTokens) {
    this(
        model,
        instructions,
        prompt,
        previousResponseId,
        toolOutputs,
        tools,
        maxOutputTokens,
        null,
        null,
        List.of());
  }

  public ModelRequest {
    if (model == null || model.isBlank()) throw new IllegalArgumentException("Model is required");
    if (instructions == null || instructions.isBlank()) {
      throw new IllegalArgumentException("Model instructions are required");
    }
    toolOutputs = toolOutputs == null ? List.of() : List.copyOf(toolOutputs);
    tools = tools == null ? List.of() : List.copyOf(tools);
    conversation = conversation == null ? List.of() : List.copyOf(conversation);
    boolean hasPrompt = prompt != null && !prompt.isBlank();
    boolean hasToolOutputs = !toolOutputs.isEmpty();
    if (hasPrompt == hasToolOutputs) {
      throw new IllegalArgumentException("Provide either a prompt or tool outputs");
    }
    if (!toolOutputs.isEmpty() && (previousResponseId == null || previousResponseId.isBlank())) {
      throw new IllegalArgumentException("Tool outputs require a previous response ID");
    }
    if (maxOutputTokens < 1) {
      throw new IllegalArgumentException("Maximum output tokens must be positive");
    }
  }
}
