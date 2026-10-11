package tech.wenisch.petri.agent.orchestration;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WorktreeExecutionReservationRepository extends JpaRepository<WorktreeExecutionReservation, UUID> {
  Optional<WorktreeExecutionReservation> findByWorkflowId(UUID workflowId);
}
