package tech.wenisch.petri.project;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectInitializationJobRepository
    extends JpaRepository<ProjectInitializationJob, UUID> {
  Optional<ProjectInitializationJob> findByProjectId(UUID projectId);
  List<ProjectInitializationJob> findByStatusInOrderByCreatedAtAsc(
      Collection<ProjectInitializationStatus> statuses);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select j from ProjectInitializationJob j where j.id = :id")
  Optional<ProjectInitializationJob> findLocked(@Param("id") UUID id);

  @Query("select j from ProjectInitializationJob j where j.status = 'PENDING' "
      + "or (j.status = 'RUNNING' and j.leaseUntil < :now) order by j.createdAt")
  List<ProjectInitializationJob> recoverable(@Param("now") Instant now);
}
