package tech.wenisch.petri.automation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectAutomationRepository extends JpaRepository<ProjectAutomation, UUID> {
  List<ProjectAutomation> findAllByProjectIdOrderByCreatedAtAsc(UUID projectId);

  Optional<ProjectAutomation> findByProjectIdAndKindAndTrigger(
      UUID projectId, AutomationKind kind, AutomationTrigger trigger);

  List<ProjectAutomation> findAllByEnabledTrue();
}
