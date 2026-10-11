package tech.wenisch.petri.agent.run;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PackageInstallRepository extends JpaRepository<PackageInstallRequest, UUID> {
  List<PackageInstallRequest> findByAgentRunIdOrderByRequestedAtAsc(UUID agentRunId);

  List<PackageInstallRequest> findByWorktreeIdAndStatusIn(
      UUID worktreeId, Collection<PackageInstallStatus> statuses);

  List<PackageInstallRequest> findByStatus(PackageInstallStatus status);

  boolean existsByAgentRunIdAndStatusIn(UUID agentRunId, Collection<PackageInstallStatus> statuses);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from PackageInstallRequest r where r.id = :id")
  Optional<PackageInstallRequest> findForUpdateById(@Param("id") UUID id);
}
