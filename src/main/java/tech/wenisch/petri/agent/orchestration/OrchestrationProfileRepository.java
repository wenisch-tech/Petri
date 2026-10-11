package tech.wenisch.petri.agent.orchestration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrchestrationProfileRepository extends JpaRepository<OrchestrationProfile, UUID> {
  List<OrchestrationProfile> findByProjectIdAndUserIdOrderByRoleAsc(UUID projectId, UUID userId);
  Optional<OrchestrationProfile> findByProjectIdAndUserIdAndRole(
      UUID projectId, UUID userId, AgentWorkflowRole role);
}
