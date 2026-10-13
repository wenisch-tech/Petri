package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.mcp.McpProjectService;
import tech.wenisch.petri.project.ProjectAccessService;

/** Which of the user's MCP servers this project's agents may use. */
@Controller
public class ProjectMcpController {
  private final ProjectAccessService access;
  private final McpProjectService mcp;

  public ProjectMcpController(ProjectAccessService access, McpProjectService mcp) {
    this.access = access;
    this.mcp = mcp;
  }

  @GetMapping("/projects/{projectId}/settings/mcp")
  String page(
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "false") boolean embedded,
      Principal principal,
      Model model) {
    model.addAttribute("project", access.requireProject(projectId, principal.getName()));
    model.addAttribute("servers", mcp.list(projectId, principal.getName()));
    return embedded ? "project-mcp :: content" : "project-mcp";
  }

  @PostMapping("/projects/{projectId}/settings/mcp")
  String save(
      @PathVariable UUID projectId,
      @RequestParam Map<String, String> form,
      Principal principal,
      RedirectAttributes redirect) {
    access.requireProject(projectId, principal.getName());
    Map<UUID, McpProjectService.Activation> requested = new HashMap<>();
    for (McpProjectService.ProjectServer server : mcp.list(projectId, principal.getName())) {
      String key = server.serverId().toString();
      requested.put(server.serverId(), new McpProjectService.Activation(
          "true".equals(form.get("enabled-" + key)), "true".equals(form.get("auto-" + key))));
    }
    try {
      mcp.save(projectId, requested, principal.getName());
      redirect.addFlashAttribute("mcpSaved", true);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("mcpError", exception.getMessage());
    }
    return "redirect:/projects/" + projectId + "/settings/mcp";
  }
}
