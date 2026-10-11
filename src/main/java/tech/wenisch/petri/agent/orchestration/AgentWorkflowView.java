package tech.wenisch.petri.agent.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tech.wenisch.petri.agent.change.ChangeProposalView;
import tech.wenisch.petri.agent.run.PackageInstallView;
import tech.wenisch.petri.agent.run.TestRunView;

public record AgentWorkflowView(
    UUID id,
    UUID chatId,
    int chatMessageIndex,
    UUID executionWorktreeId,
    String prompt,
    AgentWorkflowStatus status,
    AgentWorkflowStage currentStage,
    String baselineHead,
    Integer currentPlanRevision,
    Integer approvedPlanRevision,
    String holdReason,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt,
    List<AgentWorkflowArtifactView> artifacts,
    List<AgentWorkflowTransitionView> transitions,
    List<AgentRunView> stageRuns,
    List<ChangeProposalView> proposals,
    List<TestRunView> tests,
    List<PackageInstallView> packageInstalls) {}
