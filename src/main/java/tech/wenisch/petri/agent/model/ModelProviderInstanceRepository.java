package tech.wenisch.petri.agent.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ModelProviderInstanceRepository extends JpaRepository<ModelProviderInstance, UUID> {
  List<ModelProviderInstance> findByOwnerIdIsNullOrderByDisplayNameAsc();

  List<ModelProviderInstance> findByOwnerIdOrderByDisplayNameAsc(UUID ownerId);

  /** The oldest global instance of a kind; rows created before instances existed resolve to it. */
  Optional<ModelProviderInstance> findFirstByKindAndOwnerIdIsNullOrderByCreatedAtAsc(
      ModelProvider kind);
}
