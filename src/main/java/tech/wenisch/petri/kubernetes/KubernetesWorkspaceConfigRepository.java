package tech.wenisch.petri.kubernetes;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface KubernetesWorkspaceConfigRepository extends JpaRepository<KubernetesWorkspaceConfig, UUID> {
  Optional<KubernetesWorkspaceConfig> findByProjectId(UUID projectId);
}
