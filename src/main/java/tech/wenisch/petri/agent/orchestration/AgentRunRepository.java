package tech.wenisch.petri.agent.orchestration;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {
  List<AgentRun> findByWorktreeIdOrderByCreatedAtDesc(UUID worktreeId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select run from AgentRun run where run.id = :id")
  Optional<AgentRun> findForUpdateById(@Param("id") UUID id);
}
