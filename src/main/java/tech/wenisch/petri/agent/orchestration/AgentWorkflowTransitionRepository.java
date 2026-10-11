package tech.wenisch.petri.agent.orchestration;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AgentWorkflowTransitionRepository extends JpaRepository<AgentWorkflowTransition, UUID> {
  List<AgentWorkflowTransition> findByWorkflowIdOrderByCreatedAtAsc(UUID workflowId);
}
