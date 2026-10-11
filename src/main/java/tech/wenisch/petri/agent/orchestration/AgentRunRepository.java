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
  List<AgentRun> findAllByOrderByCreatedAtDesc();

  List<AgentRun> findByCreatedByOrderByCreatedAtDesc(UUID createdBy);

  List<AgentRun> findByWorktreeIdOrderByCreatedAtDesc(UUID worktreeId);

  List<AgentRun> findByChatIdOrderByChatMessageIndexAsc(UUID chatId);

  List<AgentRun> findByChatIdAndStatusOrderByChatMessageIndexAsc(
      UUID chatId, AgentRunStatus status);

  List<AgentRun> findByStatusOrderByCreatedAtAsc(AgentRunStatus status);

  boolean existsByWorktreeIdAndStatusIn(UUID worktreeId, java.util.Collection<AgentRunStatus> statuses);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select run from AgentRun run where run.id = :id")
  Optional<AgentRun> findForUpdateById(@Param("id") UUID id);
}
