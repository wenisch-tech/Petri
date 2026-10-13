package tech.wenisch.petri.mcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** A project's switch for one MCP server of its creator. */
@Entity
@Table(name = "project_mcp_server")
@IdClass(ProjectMcpServer.Key.class)
class ProjectMcpServer {
  @Id @Column(name = "project_id") private UUID projectId;
  @Id @Column(name = "server_id") private UUID serverId;
  @Column(nullable = false) private boolean enabled;
  @Column(name = "auto_approve", nullable = false) private boolean autoApprove;

  protected ProjectMcpServer() {}

  ProjectMcpServer(UUID projectId, UUID serverId, boolean enabled, boolean autoApprove) {
    this.projectId = projectId;
    this.serverId = serverId;
    this.enabled = enabled;
    this.autoApprove = autoApprove;
  }

  void update(boolean enabled, boolean autoApprove) {
    this.enabled = enabled;
    this.autoApprove = autoApprove;
  }

  UUID projectId() { return projectId; }
  UUID serverId() { return serverId; }
  boolean enabled() { return enabled; }
  boolean autoApprove() { return autoApprove; }

  static class Key implements Serializable {
    private UUID projectId;
    private UUID serverId;

    public Key() {}

    public Key(UUID projectId, UUID serverId) {
      this.projectId = projectId;
      this.serverId = serverId;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(projectId, key.projectId) && Objects.equals(serverId, key.serverId);
    }

    @Override
    public int hashCode() { return Objects.hash(projectId, serverId); }
  }
}
