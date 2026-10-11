package tech.wenisch.petri.agent.orchestration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.ReasoningEffort;
import tech.wenisch.petri.agent.model.TextVerbosity;

@Entity
@Table(name = "agent_workflow_role_snapshot")
class AgentWorkflowRoleSnapshot {
  @Id private UUID id;
  @Column(name = "workflow_id", nullable = false) private UUID workflowId;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private AgentWorkflowRole role;
  @Enumerated(EnumType.STRING) @Column(name = "model_provider", nullable = false, length = 32) private ModelProvider provider;
  @Column(name = "model_name", nullable = false, length = 200) private String model;
  @Column(name = "max_model_turns", nullable = false) private int maxModelTurns;
  @Column(name = "max_output_tokens", nullable = false) private int maxOutputTokens;
  @Column(name = "context_window_tokens", nullable = false) private int contextWindowTokens;
  @Enumerated(EnumType.STRING) @Column(name = "reasoning_effort", length = 16) private ReasoningEffort reasoningEffort;
  @Enumerated(EnumType.STRING) @Column(name = "text_verbosity", length = 16) private TextVerbosity textVerbosity;
  @Column(name = "max_attempts", nullable = false) private int maxAttempts;
  @Column(name = "prompt_version", nullable = false, length = 32) private String promptVersion;
  @Column(name = "prompt_addendum", columnDefinition = "TEXT") private String promptAddendum;

  protected AgentWorkflowRoleSnapshot() {}
  AgentWorkflowRoleSnapshot(UUID id, UUID workflowId, OrchestrationProfile profile) {
    this.id = id; this.workflowId = workflowId; this.role = profile.role(); this.provider = profile.provider();
    this.model = profile.model(); this.maxModelTurns = profile.maxModelTurns();
    this.maxOutputTokens = profile.maxOutputTokens(); this.contextWindowTokens = profile.contextWindowTokens();
    this.reasoningEffort = profile.reasoningEffort(); this.textVerbosity = profile.textVerbosity();
    this.maxAttempts = profile.maxAttempts(); this.promptVersion = "v1";
    this.promptAddendum = profile.promptAddendum();
  }
  AgentWorkflowRoleSnapshot(UUID id, UUID workflowId, OrchestrationRoleProfileView profile) {
    this.id = id; this.workflowId = workflowId; this.role = profile.role(); this.provider = profile.provider();
    this.model = profile.model(); this.maxModelTurns = profile.maxModelTurns();
    this.maxOutputTokens = profile.maxOutputTokens(); this.contextWindowTokens = profile.contextWindowTokens();
    this.reasoningEffort = profile.reasoningEffort(); this.textVerbosity = profile.textVerbosity();
    this.maxAttempts = profile.maxAttempts(); this.promptVersion = "v1";
    this.promptAddendum = profile.promptAddendum();
  }
  UUID workflowId() { return workflowId; } AgentWorkflowRole role() { return role; }
  ModelProvider provider() { return provider; } String model() { return model; }
  int maxModelTurns() { return maxModelTurns; } int maxOutputTokens() { return maxOutputTokens; }
  int contextWindowTokens() { return contextWindowTokens; } ReasoningEffort reasoningEffort() { return reasoningEffort; }
  TextVerbosity textVerbosity() { return textVerbosity; } int maxAttempts() { return maxAttempts; }
  String promptVersion() { return promptVersion; } String promptAddendum() { return promptAddendum; }
}
