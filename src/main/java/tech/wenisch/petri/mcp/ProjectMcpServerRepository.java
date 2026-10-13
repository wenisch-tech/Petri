package tech.wenisch.petri.mcp;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectMcpServerRepository extends JpaRepository<ProjectMcpServer, ProjectMcpServer.Key> {
  List<ProjectMcpServer> findByProjectId(UUID projectId);

  void deleteByProjectIdAndServerId(UUID projectId, UUID serverId);
}
