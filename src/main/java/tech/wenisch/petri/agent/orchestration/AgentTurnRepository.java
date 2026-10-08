package tech.wenisch.petri.agent.orchestration;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AgentTurnRepository extends JpaRepository<AgentTurn, UUID> {
  List<AgentTurn> findByRunIdOrderByIndexAsc(UUID runId);
}
