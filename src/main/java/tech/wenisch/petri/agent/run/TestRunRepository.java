package tech.wenisch.petri.agent.run;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface TestRunRepository extends JpaRepository<TestRun, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select testRun from TestRun testRun where testRun.id = :id")
  Optional<TestRun> findForUpdateById(@Param("id") UUID id);
}
