package tech.wenisch.petri.agent.orchestration;

import java.util.UUID;

public record AgentWorkflowStartView(
    AgentWorkflowView workflow,
    UUID chatId,
    UUID worktreeId,
    String navigationUrl) {}
