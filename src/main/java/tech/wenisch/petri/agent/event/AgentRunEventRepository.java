package tech.wenisch.petri.agent.event;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AgentRunEventRepository extends JpaRepository<AgentRunEvent, UUID> {
  List<AgentRunEvent> findTop200ByWorktreeIdOrderByOccurredAtDesc(UUID worktreeId);
}
