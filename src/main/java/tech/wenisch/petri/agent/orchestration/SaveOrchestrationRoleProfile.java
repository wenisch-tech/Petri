package tech.wenisch.petri.agent.orchestration;

import java.util.UUID;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.ReasoningEffort;
import tech.wenisch.petri.agent.model.TextVerbosity;

public record SaveOrchestrationRoleProfile(
    AgentWorkflowRole role,
    ModelProvider provider,
    UUID providerInstanceId,
    String model,
    Integer maxModelTurns,
    Integer maxOutputTokens,
    Integer contextWindowTokens,
    ReasoningEffort reasoningEffort,
    TextVerbosity textVerbosity,
    Integer maxAttempts,
    String promptAddendum) {}
