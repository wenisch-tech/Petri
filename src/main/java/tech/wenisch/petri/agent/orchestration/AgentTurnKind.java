package tech.wenisch.petri.agent.orchestration;

public enum AgentTurnKind {
  USER_PROMPT,
  MODEL_RESPONSE,
  TOOL_CALL,
  TOOL_RESULT,
  ERROR
}
