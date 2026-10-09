package tech.wenisch.petri.agent.orchestration;

import java.util.List;
import java.util.UUID;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.ModelToolOutput;

record AgentExecutionState(
    UUID runId,
    UUID worktreeId,
    ModelProvider provider,
    String model,
    String prompt,
    AgentRunStatus status,
    int maxModelTurns,
    int modelTurnCount,
    String previousResponseId,
    List<AgentPendingToolCall> pendingToolCalls,
    List<ModelToolOutput> toolOutputs) {

  boolean terminal() {
    return switch (status) {
      case COMPLETED, FAILED, BUDGET_EXHAUSTED, CANCELLED -> true;
      default -> false;
    };
  }

  boolean initialModelRequest() {
    return modelTurnCount == 0;
  }
}

