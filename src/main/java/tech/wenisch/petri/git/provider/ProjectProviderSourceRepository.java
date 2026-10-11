package tech.wenisch.petri.git.provider;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectProviderSourceRepository
    extends JpaRepository<ProjectProviderSource, UUID> {
  boolean existsByOwnerIdAndProviderInstanceIdAndExternalRepositoryId(
      UUID ownerId, UUID providerInstanceId, String externalRepositoryId);
  Optional<ProjectProviderSource> findByProjectId(UUID projectId);
}
