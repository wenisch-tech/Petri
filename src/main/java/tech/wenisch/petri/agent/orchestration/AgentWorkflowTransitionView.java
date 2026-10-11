package tech.wenisch.petri.agent.orchestration;

import java.time.Instant;
import java.util.UUID;

public record AgentWorkflowTransitionView(
    UUID id, AgentWorkflowStage fromStage, AgentWorkflowStage toStage,
    WorkflowGateOutcome outcome, String actor, String reason, Instant createdAt) {}
