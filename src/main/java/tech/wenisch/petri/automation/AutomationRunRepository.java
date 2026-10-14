package tech.wenisch.petri.automation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutomationRunRepository extends JpaRepository<AutomationRun, UUID> {
  Optional<AutomationRun> findByProjectIdAndKindAndExternalRefAndHeadSha(
      UUID projectId, AutomationKind kind, String externalRef, String headSha);

  /** Every run of one pull request, newest first. */
  List<AutomationRun> findByStatus(AutomationRunStatus status);

  List<AutomationRun> findByProjectIdAndKindAndExternalRefOrderByCreatedAtDesc(
      UUID projectId, AutomationKind kind, String externalRef);

  List<AutomationRun> findTop20ByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
