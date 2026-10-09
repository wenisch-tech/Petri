package tech.wenisch.petri.agent.orchestration;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AgentChatSummaryRepository extends JpaRepository<AgentChatSummary, UUID> {
  List<AgentChatSummary> findByChatIdOrderByThroughMessageIndexAsc(UUID chatId);
}
