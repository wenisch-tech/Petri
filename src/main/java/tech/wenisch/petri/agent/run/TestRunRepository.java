package tech.wenisch.petri.agent.run;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface TestRunRepository extends JpaRepository<TestRun, UUID> {
  List<TestRun> findByAgentRunIdOrderByRequestedAtAsc(UUID agentRunId);

  boolean existsByWorktreeIdAndStatusIn(UUID worktreeId, java.util.Collection<TestRunStatus> statuses);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select testRun from TestRun testRun where testRun.id = :id")
  Optional<TestRun> findForUpdateById(@Param("id") UUID id);
}
