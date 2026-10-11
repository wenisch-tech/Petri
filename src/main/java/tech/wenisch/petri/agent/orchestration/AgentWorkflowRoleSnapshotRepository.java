package tech.wenisch.petri.agent.orchestration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AgentWorkflowRoleSnapshotRepository extends JpaRepository<AgentWorkflowRoleSnapshot, UUID> {
  List<AgentWorkflowRoleSnapshot> findByWorkflowIdOrderByRoleAsc(UUID workflowId);
  Optional<AgentWorkflowRoleSnapshot> findByWorkflowIdAndRole(UUID workflowId, AgentWorkflowRole role);
}
