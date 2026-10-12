package tech.wenisch.petri.agent.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ModelUserApiKeyRepository extends JpaRepository<ModelUserApiKey, UUID> {
  Optional<ModelUserApiKey> findByInstanceIdAndUserId(UUID instanceId, UUID userId);

  List<ModelUserApiKey> findByUserId(UUID userId);

  List<ModelUserApiKey> findByInstanceId(UUID instanceId);
}
