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
    List<ModelConversationMessage> conversation,
    boolean jsonObjectOutput,
    ModelCredentialRef credential) {

  public ModelRequest(
      String model,
      String instructions,
      String prompt,
      String previousResponseId,
      List<ModelToolOutput> toolOutputs,
      List<ToolDefinition> tools,
      int maxOutputTokens,
      ReasoningEffort reasoningEffort,
      TextVerbosity textVerbosity,
      List<ModelConversationMessage> conversation,
      boolean jsonObjectOutput) {
    this(
        model, instructions, prompt, previousResponseId, toolOutputs, tools, maxOutputTokens,
        reasoningEffort, textVerbosity, conversation, jsonObjectOutput, null);
  }

  public ModelRequest(
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
    this(
        model, instructions, prompt, previousResponseId, toolOutputs, tools, maxOutputTokens,
        reasoningEffort, textVerbosity, conversation, false);
  }

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
        reasoningEffort, textVerbosity, List.of(), false);
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
        List.of(),
        false);
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

  public ModelRequest withJsonObjectOutput(boolean enabled) {
    return new ModelRequest(
        model, instructions, prompt, previousResponseId, toolOutputs, tools, maxOutputTokens,
        reasoningEffort, textVerbosity, conversation, enabled, credential);
  }

  /** The same request, sent with the given instance and on behalf of the given user. */
  public ModelRequest withCredential(ModelCredentialRef value) {
    return new ModelRequest(
        model, instructions, prompt, previousResponseId, toolOutputs, tools, maxOutputTokens,
        reasoningEffort, textVerbosity, conversation, jsonObjectOutput, value);
  }
}
