package tech.wenisch.petri.agent.orchestration;

import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.ReasoningEffort;
import tech.wenisch.petri.agent.model.TextVerbosity;

public record CreateAgentRunCommand(
    String prompt,
    ModelProvider provider,
    Integer maxModelTurns,
    String model,
    Integer maxOutputTokens,
    Integer contextWindowTokens,
    ReasoningEffort reasoningEffort,
    TextVerbosity textVerbosity) {

  public CreateAgentRunCommand(
      String prompt, ModelProvider provider, Integer maxModelTurns) {
    this(prompt, provider, maxModelTurns, null, null, null, null, null);
  }

  public CreateAgentRunCommand(
      String prompt,
      ModelProvider provider,
      Integer maxModelTurns,
      String model,
      Integer maxOutputTokens,
      ReasoningEffort reasoningEffort,
      TextVerbosity textVerbosity) {
    this(prompt, provider, maxModelTurns, model, maxOutputTokens, null, reasoningEffort,
        textVerbosity);
  }
}
