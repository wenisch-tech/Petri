package tech.wenisch.petri.mcp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

/** Which of its creator's MCP servers a project uses, and the tools that follow from it. */
@Service
public class McpProjectService {
  static final int MAX_SCHEMA_CHARS = 20_000;
  static final int MAX_DESCRIPTION = 1000;

  /** One of the user's servers as the project's MCP tab shows it. */
  public record ProjectServer(
      UUID serverId, String name, String url, McpServerStatus status, McpAuthType authType,
      boolean signedIn, int toolCount, int readOnlyCount, boolean enabled, boolean autoApprove) {}

  public record Activation(boolean enabled, boolean autoApprove) {}

  /** A tool the agent of a project may call. */
  public record ActiveTool(
      String exposedName, UUID serverId, String serverName, String remoteName, String description,
      Map<String, Object> inputSchema, boolean readOnly, boolean autoApprove) {}

  private final McpServerRepository servers;
  private final ProjectMcpServerRepository activations;
  private final ProjectRepository projects;
  private final AppUserRepository users;
  private final McpServerService serverService;
  private final AuditLogService audit;

  public McpProjectService(
      McpServerRepository servers, ProjectMcpServerRepository activations, ProjectRepository projects,
      AppUserRepository users, McpServerService serverService, AuditLogService audit) {
    this.servers = servers;
    this.activations = activations;
    this.projects = projects;
    this.users = users;
    this.serverService = serverService;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<ProjectServer> list(UUID projectId, String actorEmail) {
    AppUser actor = actor(actorEmail);
    Map<UUID, ProjectMcpServer> byServer = new LinkedHashMap<>();
    activations.findByProjectId(projectId).forEach(value -> byServer.put(value.serverId(), value));
    List<ProjectServer> result = new ArrayList<>();
    for (McpServer server : servers.findByOwnerIdOrderByNameAsc(actor.getId())) {
      ProjectMcpServer activation = byServer.get(server.id());
      List<McpClient.Tool> tools = serverService.cachedTools(server);
      result.add(new ProjectServer(
          server.id(), server.name(), server.url(), server.status(), server.authType(),
          server.oauthAccessCiphertext() != null, tools.size(),
          (int) tools.stream().filter(McpClient.Tool::readOnly).count(),
          activation != null && activation.enabled(), activation != null && activation.autoApprove()));
    }
    return result;
  }

  /** Replaces the activation of the actor's own servers; unknown or foreign ids are ignored. */
  @Transactional
  public void save(UUID projectId, Map<UUID, Activation> requested, String actorEmail) {
    AppUser actor = actor(actorEmail);
    Project project = projects.findByIdAndCreatedBy(projectId, actor.getId())
        .orElseThrow(() -> new IllegalArgumentException("Unknown project"));
    List<String> enabled = new ArrayList<>();
    for (McpServer server : servers.findByOwnerIdOrderByNameAsc(actor.getId())) {
      Activation wanted = requested.getOrDefault(server.id(), new Activation(false, false));
      ProjectMcpServer row = activations.findById(new ProjectMcpServer.Key(project.getId(), server.id()))
          .orElse(null);
      if (row == null && !wanted.enabled()) continue;
      if (row == null) row = new ProjectMcpServer(project.getId(), server.id(), wanted.enabled(), wanted.autoApprove());
      else row.update(wanted.enabled(), wanted.autoApprove());
      activations.save(row);
      if (wanted.enabled()) enabled.add(server.name() + (wanted.autoApprove() ? " (auto-approve)" : ""));
    }
    audit.record(actor.getEmail(), "PROJECT_MCP_SAVED", "PROJECT", projectId,
        "Active MCP servers: " + (enabled.isEmpty() ? "none" : String.join(", ", enabled)));
  }

  /**
   * The tools of the servers a project activated, with model-safe unique names. Only servers owned
   * by the project's creator count, and only those that are reachable.
   */
  @Transactional(readOnly = true)
  public List<ActiveTool> activeTools(UUID projectId) {
    Project project = projects.findById(projectId).orElse(null);
    if (project == null) return List.of();
    Set<String> taken = new HashSet<>();
    List<ActiveTool> tools = new ArrayList<>();
    for (ProjectMcpServer activation : activations.findByProjectId(projectId)) {
      if (!activation.enabled()) continue;
      McpServer server = servers.findByIdAndOwnerId(activation.serverId(), project.getCreatedBy()).orElse(null);
      if (server == null || server.status() == McpServerStatus.ERROR) continue;
      for (McpClient.Tool tool : serverService.cachedTools(server)) {
        String exposed = McpToolNaming.expose(server.name(), tool.name(), taken);
        taken.add(exposed);
        String description = tool.description() == null ? "" : tool.description();
        if (description.length() > MAX_DESCRIPTION) description = description.substring(0, MAX_DESCRIPTION - 1) + "…";
        Map<String, Object> schema = tool.inputSchema() == null ? Map.of("type", "object") : tool.inputSchema();
        if (schema.toString().length() > MAX_SCHEMA_CHARS) continue; // an oversized schema is not worth the context
        tools.add(new ActiveTool(
            exposed, server.id(), server.name(), tool.name(),
            "[" + server.name() + " via MCP] " + description, schema, tool.readOnly(),
            activation.autoApprove()));
      }
    }
    return tools;
  }

  private AppUser actor(String email) {
    return users.findByEmailIgnoreCase(email)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }
}
