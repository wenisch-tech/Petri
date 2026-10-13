package tech.wenisch.petri.agent.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ModelProviderModelRepository extends JpaRepository<ModelProviderModel, UUID> {
  List<ModelProviderModel> findByInstanceIdIn(Collection<UUID> instanceIds);

  void deleteByInstanceIdAndCustomFalse(UUID instanceId);
}
