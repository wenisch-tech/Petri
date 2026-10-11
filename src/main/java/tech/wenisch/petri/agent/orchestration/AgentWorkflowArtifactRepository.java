package tech.wenisch.petri.agent.orchestration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AgentWorkflowArtifactRepository extends JpaRepository<AgentWorkflowArtifact, UUID> {
  List<AgentWorkflowArtifact> findByWorkflowIdOrderByCreatedAtAsc(UUID workflowId);
  List<AgentWorkflowArtifact> findByWorkflowIdAndKindOrderByRevisionAsc(
      UUID workflowId, WorkflowArtifactKind kind);
  Optional<AgentWorkflowArtifact> findFirstByWorkflowIdAndKindOrderByRevisionDesc(
      UUID workflowId, WorkflowArtifactKind kind);
  Optional<AgentWorkflowArtifact> findByAgentRunId(UUID agentRunId);
}
