package tech.wenisch.petri.agent.model;

public enum ModelProvider {
  OPENAI("OpenAI", "Responses API", true, "https://api.openai.com/v1"),
  LITELLM("LiteLLM", "Responses API", false, "http://localhost:4000/v1"),
  OLLAMA("Ollama", "Chat Completions", false, "http://localhost:11434/v1"),
  OPENAI_COMPATIBLE(
      "OpenAI-compatible", "Chat Completions", false, "http://localhost:8000/v1");

  private final String displayName;
  private final String apiStyle;
  private final boolean apiKeyRequired;
  private final String defaultBaseUrl;

  ModelProvider(
      String displayName, String apiStyle, boolean apiKeyRequired, String defaultBaseUrl) {
    this.displayName = displayName;
    this.apiStyle = apiStyle;
    this.apiKeyRequired = apiKeyRequired;
    this.defaultBaseUrl = defaultBaseUrl;
  }

  public String displayName() { return displayName; }
  public String apiStyle() { return apiStyle; }
  public boolean apiKeyRequired() { return apiKeyRequired; }
  public String defaultBaseUrl() { return defaultBaseUrl; }
}
