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
  List<AgentRun> findByWorkflowIdIsNullOrderByCreatedAtDesc();

  List<AgentRun> findByCreatedByOrderByCreatedAtDesc(UUID createdBy);
  List<AgentRun> findByCreatedByAndWorkflowIdIsNullOrderByCreatedAtDesc(UUID createdBy);

  List<AgentRun> findByWorktreeIdOrderByCreatedAtDesc(UUID worktreeId);
  List<AgentRun> findByWorktreeIdAndWorkflowIdIsNullOrderByCreatedAtDesc(UUID worktreeId);

  /** A user's runs on any worktree (also removed ones) of one project, newest first. */
  @Query("select run from AgentRun run where run.createdBy = :userId "
      + "and run.worktreeId in (select tree.id from ProjectWorktree tree where tree.projectId = :projectId) "
      + "and (:withWorkflowStages = true or run.workflowId is null) order by run.createdAt desc")
  List<AgentRun> findForProject(
      @Param("projectId") UUID projectId, @Param("userId") UUID userId,
      @Param("withWorkflowStages") boolean withWorkflowStages);

  List<AgentRun> findByChatIdOrderByChatMessageIndexAsc(UUID chatId);

  List<AgentRun> findByChatIdAndWorkflowIdIsNullOrderByChatMessageIndexAsc(UUID chatId);

  List<AgentRun> findByWorkflowIdOrderByCreatedAtAsc(UUID workflowId);

  long countByWorkflowIdAndWorkflowRole(UUID workflowId, AgentWorkflowRole role);

  List<AgentRun> findByChatIdAndStatusOrderByChatMessageIndexAsc(
      UUID chatId, AgentRunStatus status);

  List<AgentRun> findByStatusOrderByCreatedAtAsc(AgentRunStatus status);

  boolean existsByWorktreeIdAndStatusIn(UUID worktreeId, java.util.Collection<AgentRunStatus> statuses);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select run from AgentRun run where run.id = :id")
  Optional<AgentRun> findForUpdateById(@Param("id") UUID id);
}
