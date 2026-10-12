package tech.wenisch.petri.agent.model;

/** Create or update a model provider instance. A blank API key keeps the stored key. */
public record SaveModelProviderCommand(
    ModelProvider provider,
    String displayName,
    String baseUrl,
    String model,
    String apiKey,
    boolean clearApiKey,
    Integer maxOutputTokens,
    Integer contextWindowTokens,
    ReasoningEffort reasoningEffort,
    TextVerbosity textVerbosity,
    boolean enabled) {}
