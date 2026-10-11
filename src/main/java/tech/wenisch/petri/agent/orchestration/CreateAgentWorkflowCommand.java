package tech.wenisch.petri.agent.orchestration;

import java.util.UUID;
import java.util.List;

public record CreateAgentWorkflowCommand(
    UUID chatId,
    String prompt,
    WorkflowBranchMode branchMode,
    String branchName,
    boolean confirmedDefaultBranch,
    List<SaveOrchestrationRoleProfile> roleOverrides) {}
