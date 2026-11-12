package tech.wenisch.petri.kubernetes;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WorkspacePodStateRepository extends JpaRepository<WorkspacePodState, UUID> {
  List<WorkspacePodState> findByPhaseIn(java.util.Collection<WorkspacePodPhase> phases);
}
