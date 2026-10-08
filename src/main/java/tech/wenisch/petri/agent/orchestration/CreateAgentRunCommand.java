package tech.wenisch.petri.agent.orchestration;

import tech.wenisch.petri.agent.model.ModelProvider;

public record CreateAgentRunCommand(
    String prompt,
    ModelProvider provider,
    Integer maxModelTurns) {}
