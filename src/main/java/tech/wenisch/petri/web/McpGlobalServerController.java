package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.mcp.McpAuthType;
import tech.wenisch.petri.mcp.McpServerService;
import tech.wenisch.petri.mcp.McpTransport;

/**
 * Instance-wide MCP servers, managed by administrators. Without a stored token or headers every
 * user adds their own credential; each project still decides whether to use the server.
 */
@Controller
public class McpGlobalServerController {
  private static final String HOME = "redirect:/admin/mcp-servers";

  private final McpServerService servers;

  public McpGlobalServerController(McpServerService servers) {
    this.servers = servers;
  }

  @GetMapping("/admin/mcp-servers")
  String list(Principal principal, Model model) {
    model.addAttribute("servers", servers.listGlobal(principal.getName()));
    model.addAttribute("transports", McpTransport.values());
    model.addAttribute("authTypes", McpAuthType.values());
    model.addAttribute("basePath", "/admin/mcp-servers");
    model.addAttribute("adminScope", true);
    return "mcp-servers";
  }

  @PostMapping("/admin/mcp-servers")
  String save(
      @RequestParam(required = false) UUID id,
      @RequestParam String name,
      @RequestParam String url,
      @RequestParam(defaultValue = "AUTO") McpTransport transport,
      @RequestParam(defaultValue = "NONE") McpAuthType authType,
      @RequestParam(required = false) String secret,
      @RequestParam(required = false) String headers,
      @RequestParam(required = false) String oauthClientId,
      @RequestParam(required = false) String oauthClientSecret,
      @RequestParam(required = false) String oauthScopes,
      Principal principal,
      RedirectAttributes redirect) {
    try {
      var saved = servers.saveGlobal(id, new McpServerService.SaveCommand(
          name, url, transport, authType, authType == McpAuthType.HEADERS ? headers : secret,
          oauthClientId, oauthClientSecret, oauthScopes), principal.getName());
      redirect.addFlashAttribute("notice", "Saved " + saved.name());
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return HOME;
  }

  @PostMapping("/admin/mcp-servers/{id}/delete")
  String delete(@PathVariable UUID id, Principal principal, RedirectAttributes redirect) {
    try {
      servers.delete(id, principal.getName());
      redirect.addFlashAttribute("notice", "MCP server removed");
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return HOME;
  }

  @PostMapping("/admin/mcp-servers/{id}/refresh")
  String refresh(@PathVariable UUID id, Principal principal, RedirectAttributes redirect) {
    try {
      var view = servers.refreshTools(id, principal.getName());
      if (view.lastError() == null) {
        redirect.addFlashAttribute("notice", "Found " + view.tools().size() + " tools on " + view.name());
      } else {
        redirect.addFlashAttribute("error", view.name() + ": " + view.lastError());
      }
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return HOME;
  }
}
