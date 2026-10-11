package tech.wenisch.petri.agent.orchestration;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AgentWorkflowRepository extends JpaRepository<AgentWorkflow, UUID> {
  List<AgentWorkflow> findByChatIdOrderByChatMessageIndexAsc(UUID chatId);
  List<AgentWorkflow> findByExecutionWorktreeIdAndStatusInOrderByCreatedAtAsc(
      UUID worktreeId, java.util.Collection<AgentWorkflowStatus> statuses);
  List<AgentWorkflow> findByStatusOrderByCreatedAtAsc(AgentWorkflowStatus status);
  List<AgentWorkflow> findByStatusInOrderByCreatedAtAsc(
      java.util.Collection<AgentWorkflowStatus> statuses);
  List<AgentWorkflow> findByExecutionWorktreeIdAndStatusAndCurrentStageOrderByCreatedAtAsc(
      UUID worktreeId, AgentWorkflowStatus status, AgentWorkflowStage currentStage);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select workflow from AgentWorkflow workflow where workflow.id = :id")
  Optional<AgentWorkflow> findForUpdateById(@Param("id") UUID id);
}
