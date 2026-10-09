package tech.wenisch.petri.agent.change;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChangeProposalRepository extends JpaRepository<ChangeProposal, UUID> {
  List<ChangeProposal> findByAgentRunIdOrderByCreatedAtAsc(UUID agentRunId);
}
