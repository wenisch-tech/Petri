package tech.wenisch.petri.workspace;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WorkspaceModelPreferenceRepository extends JpaRepository<WorkspaceModelPreference, UUID> {
  Optional<WorkspaceModelPreference> findByProjectIdAndUserId(UUID projectId, UUID userId);
}
