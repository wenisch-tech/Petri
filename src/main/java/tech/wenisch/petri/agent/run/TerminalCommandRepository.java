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

interface TerminalCommandRepository extends JpaRepository<TerminalCommandRequest, UUID> {
  List<TerminalCommandRequest> findByAgentRunIdOrderByRequestedAtAsc(UUID agentRunId);

  List<TerminalCommandRequest> findByWorktreeIdAndStatusIn(
      UUID worktreeId, Collection<TerminalCommandStatus> statuses);

  boolean existsByAgentRunIdAndStatusIn(UUID agentRunId, Collection<TerminalCommandStatus> statuses);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from TerminalCommandRequest c where c.id = :id")
  Optional<TerminalCommandRequest> findForUpdateById(@Param("id") UUID id);
}
