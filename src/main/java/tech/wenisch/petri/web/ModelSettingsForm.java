package tech.wenisch.petri.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tech.wenisch.petri.agent.model.ReasoningEffort;
import tech.wenisch.petri.agent.model.TextVerbosity;

public class ModelSettingsForm {
  @Size(max = 12000)
  private String apiKey;

  @NotBlank
  @Size(max = 200)
  private String model;

  @Size(max = 2048)
  private String baseUrl;

  @Min(16)
  @Max(1_000_000)
  private Integer maxOutputTokens;

  @Min(2048)
  @Max(10_000_000)
  private Integer contextWindowTokens;

  private ReasoningEffort reasoningEffort;
  private TextVerbosity textVerbosity;

  public String getApiKey() { return apiKey; }
  public void setApiKey(String apiKey) { this.apiKey = apiKey; }
  public String getModel() { return model; }
  public void setModel(String model) { this.model = model; }
  public String getBaseUrl() { return baseUrl; }
  public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
  public Integer getMaxOutputTokens() { return maxOutputTokens; }
  public void setMaxOutputTokens(Integer maxOutputTokens) {
    this.maxOutputTokens = maxOutputTokens;
  }
  public Integer getContextWindowTokens() { return contextWindowTokens; }
  public void setContextWindowTokens(Integer contextWindowTokens) {
    this.contextWindowTokens = contextWindowTokens;
  }
  public ReasoningEffort getReasoningEffort() { return reasoningEffort; }
  public void setReasoningEffort(ReasoningEffort reasoningEffort) {
    this.reasoningEffort = reasoningEffort;
  }
  public TextVerbosity getTextVerbosity() { return textVerbosity; }
  public void setTextVerbosity(TextVerbosity textVerbosity) {
    this.textVerbosity = textVerbosity;
  }
}
