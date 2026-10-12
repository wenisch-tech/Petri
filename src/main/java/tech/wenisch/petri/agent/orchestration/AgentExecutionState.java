package tech.wenisch.petri.agent.orchestration;

import java.util.List;
import java.util.UUID;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.ModelConversationMessage;
import tech.wenisch.petri.agent.model.ModelToolOutput;
import tech.wenisch.petri.agent.model.ReasoningEffort;
import tech.wenisch.petri.agent.model.TextVerbosity;

record AgentExecutionState(
    UUID runId,
    UUID chatId,
    UUID worktreeId,
    ModelProvider provider,
    UUID providerInstanceId,
    UUID createdBy,
    String model,
    String prompt,
    AgentRunStatus status,
    int maxModelTurns,
    int maxOutputTokens,
    ReasoningEffort reasoningEffort,
    TextVerbosity textVerbosity,
    int modelTurnCount,
    String previousResponseId,
    List<AgentPendingToolCall> pendingToolCalls,
    List<ModelToolOutput> toolOutputs,
    List<ModelConversationMessage> conversation,
    UUID workflowId,
    AgentWorkflowRole workflowRole,
    AgentWorkflowStage workflowPhase,
    String instructionsSnapshot) {

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
