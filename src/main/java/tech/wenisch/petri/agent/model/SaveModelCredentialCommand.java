package tech.wenisch.petri.agent.model;

public record SaveModelCredentialCommand(
    String apiKey,
    String model,
    Integer maxOutputTokens,
    ReasoningEffort reasoningEffort,
    TextVerbosity textVerbosity,
    String baseUrl) {

  public SaveModelCredentialCommand(String apiKey, String model) {
    this(apiKey, model, null, null, null, null);
  }

  public SaveModelCredentialCommand(
      String apiKey,
      String model,
      Integer maxOutputTokens,
      ReasoningEffort reasoningEffort,
      TextVerbosity textVerbosity) {
    this(apiKey, model, maxOutputTokens, reasoningEffort, textVerbosity, null);
  }
}
