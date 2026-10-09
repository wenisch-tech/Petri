package tech.wenisch.petri.agent.orchestration;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AgentChatRepository extends JpaRepository<AgentChat, UUID> {
  List<AgentChat> findAllByOrderByUpdatedAtDesc();

  List<AgentChat> findByCreatedByOrderByUpdatedAtDesc(UUID createdBy);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select chat from AgentChat chat where chat.id = :id")
  Optional<AgentChat> findForUpdateById(@Param("id") UUID id);
}
