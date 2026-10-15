package tech.wenisch.petri.packages;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackageRemoteRepository extends JpaRepository<PackageRemote, UUID> {
  List<PackageRemote> findAllByProjectIdIsNullOrderByEcosystemAscPositionAscCreatedAtAsc();

  List<PackageRemote> findAllByProjectIdOrderByEcosystemAscPositionAscCreatedAtAsc(UUID projectId);
}
