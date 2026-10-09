package tech.wenisch.petri.agent.orchestration;

import java.util.UUID;

record AgentRunTerminalEvent(UUID runId, UUID chatId) {}
