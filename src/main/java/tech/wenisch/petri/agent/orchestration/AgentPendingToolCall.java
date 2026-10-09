package tech.wenisch.petri.agent.orchestration;

record AgentPendingToolCall(String responseId, String callId, String name, String argumentsJson) {}

