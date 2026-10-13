package tech.wenisch.petri.mcp;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface McpToolCallRepository extends JpaRepository<McpToolCall, UUID> {
  @Query("select c from McpToolCall c where c.agentRunId in "
      + "(select r.id from AgentRun r where r.chatId = :chatId) order by c.createdAt")
  List<McpToolCall> findByChat(@Param("chatId") UUID chatId);
}
