package tech.wenisch.petri.agent.orchestration;

public enum AgentTurnKind {
  USER_PROMPT,
  MODEL_RESPONSE,
  TOOL_CALL,
  TOOL_RESULT,
  ERROR,
  /** Loop guard intervention; never part of the conversation sent to the model. */
  LOOP_GUARD
}
