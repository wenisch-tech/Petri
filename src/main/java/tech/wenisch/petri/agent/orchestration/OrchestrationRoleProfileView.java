package tech.wenisch.petri.agent.orchestration;

import java.util.UUID;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.ReasoningEffort;
import tech.wenisch.petri.agent.model.TextVerbosity;

public record OrchestrationRoleProfileView(
    AgentWorkflowRole role,
    ModelProvider provider,
    UUID providerInstanceId,
    String model,
    int maxModelTurns,
    int maxOutputTokens,
    int contextWindowTokens,
    ReasoningEffort reasoningEffort,
    TextVerbosity textVerbosity,
    int maxAttempts,
    String promptAddendum,
    boolean persisted) {}
