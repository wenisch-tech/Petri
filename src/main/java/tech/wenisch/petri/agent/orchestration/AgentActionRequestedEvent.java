package tech.wenisch.petri.agent.orchestration;

import java.util.UUID;

/** Published after an approval-gated agent action was durably created. */
public record AgentActionRequestedEvent(UUID agentRunId, UUID actionId, AgentActionKind kind) {
  public enum AgentActionKind { CHANGE_PROPOSAL, TEST_RUN, PACKAGE_INSTALL }
}
