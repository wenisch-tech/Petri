package tech.wenisch.petri.agent.orchestration;

import java.time.Instant;
import java.util.UUID;

public record AgentWorkflowArtifactView(
    UUID id, UUID agentRunId, WorkflowArtifactKind kind, int revision,
    String contentJson, String displayMarkdown, Instant createdAt) {}
