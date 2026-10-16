package tech.wenisch.petri.web;

import jakarta.servlet.http.HttpSession;
import java.security.MessageDigest;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tech.wenisch.petri.mcp.McpOAuthService;
import tech.wenisch.petri.mcp.McpAuthType;
import tech.wenisch.petri.mcp.McpServerService;
import tech.wenisch.petri.mcp.McpTransport;

/** A user's own MCP servers (like Git accounts): add, authenticate, test and remove. */
@Controller
public class McpServerController {
  private static final String SESSION_KEY = "petri.mcp.oauth";

  private final McpServerService servers;
  private final McpOAuthService oauth;

  public McpServerController(McpServerService servers, McpOAuthService oauth) {
    this.servers = servers;
    this.oauth = oauth;
  }

  @GetMapping("/mcp-servers")
  String list(Principal principal, Model model) {
    model.addAttribute("servers", servers.list(principal.getName()));
    model.addAttribute("transports", McpTransport.values());
    model.addAttribute("authTypes", McpAuthType.values());
    model.addAttribute("basePath", "/mcp-servers");
    model.addAttribute("adminScope", false);
    return "mcp-servers";
  }

  @PostMapping("/mcp-servers")
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
      var saved = servers.save(id, new McpServerService.SaveCommand(
          name, url, transport, authType, authType == McpAuthType.HEADERS ? headers : secret, oauthClientId, oauthClientSecret, oauthScopes),
          principal.getName());
      redirect.addFlashAttribute("notice", "Saved " + saved.name());
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/mcp-servers";
  }

  @PostMapping("/mcp-servers/{id}/delete")
  String delete(@PathVariable UUID id, Principal principal, RedirectAttributes redirect) {
    try {
      servers.delete(id, principal.getName());
      redirect.addFlashAttribute("notice", "MCP server removed");
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/mcp-servers";
  }

  /** A user's own credential for an instance-wide server that stores none for everyone. */
  @PostMapping("/mcp-servers/{id}/credential")
  String saveCredential(
      @PathVariable UUID id,
      @RequestParam(required = false) String secret,
      @RequestParam(required = false) String headers,
      Principal principal,
      RedirectAttributes redirect) {
    try {
      var text = headers != null && !headers.isBlank() ? headers : secret;
      servers.saveCredential(id, text, principal.getName());
      redirect.addFlashAttribute("notice", "Your credential was saved");
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/mcp-servers";
  }

  @PostMapping("/mcp-servers/{id}/credential/delete")
  String clearCredential(@PathVariable UUID id, Principal principal, RedirectAttributes redirect) {
    servers.clearCredential(id, principal.getName());
    redirect.addFlashAttribute("notice", "Your credential was removed");
    return "redirect:/mcp-servers";
  }

  @PostMapping("/mcp-servers/{id}/refresh")
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
    return "redirect:/mcp-servers";
  }

  /** Starts the OAuth sign-in: discovery, optional registration, then the server's own page. */
  @PostMapping("/mcp-servers/{id}/oauth/start")
  String startOauth(
      @PathVariable UUID id, Principal principal, HttpSession session, RedirectAttributes redirect) {
    try {
      String callback = ServletUriComponentsBuilder.fromCurrentContextPath()
          .path("/mcp-servers/oauth/callback").build().toUriString();
      var started = oauth.begin(id, principal.getName(), callback);
      @SuppressWarnings("unchecked")
      Map<String, McpOAuthService.Attempt> attempts =
          (Map<String, McpOAuthService.Attempt>) session.getAttribute(SESSION_KEY);
      if (attempts == null) attempts = new HashMap<>();
      attempts.entrySet().removeIf(entry -> McpOAuthService.expired(entry.getValue(), java.time.Instant.now()));
      attempts.put(started.attempt().state(), started.attempt());
      session.setAttribute(SESSION_KEY, new HashMap<>(attempts));
      return "redirect:" + started.authorizationUrl();
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
      return "redirect:" + home(id, principal);
    }
  }

  @GetMapping("/mcp-servers/oauth/callback")
  String oauthCallback(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String error,
      Principal principal,
      HttpSession session,
      RedirectAttributes redirect) {
    @SuppressWarnings("unchecked")
    Map<String, McpOAuthService.Attempt> attempts =
        (Map<String, McpOAuthService.Attempt>) session.getAttribute(SESSION_KEY);
    McpOAuthService.Attempt attempt = null;
    if (attempts != null && state != null) {
      for (var entry : attempts.entrySet()) {
        // Constant-time comparison of the state, and the attempt is single use.
        if (MessageDigest.isEqual(entry.getKey().getBytes(), state.getBytes())) attempt = entry.getValue();
      }
      if (attempt != null) {
        Map<String, McpOAuthService.Attempt> rest = new HashMap<>(attempts);
        rest.remove(attempt.state());
        session.setAttribute(SESSION_KEY, rest);
      }
    }
    try {
      if (attempt == null) throw new IllegalStateException("The sign-in is no longer valid. Start again.");
      if (error != null) throw new IllegalStateException("The server declined the sign-in: " + error);
      if (code == null || code.isBlank()) throw new IllegalStateException("The server returned no code");
      oauth.complete(attempt, code, principal.getName());
      redirect.addFlashAttribute("notice", "Signed in. Use Test to list the server's tools.");
      return "redirect:" + home(attempt.serverId(), principal);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:" + (attempt == null ? "/mcp-servers" : home(attempt.serverId(), principal));
  }

  /** Instance-wide servers are managed on the administration page. */
  private String home(UUID serverId, Principal principal) {
    try {
      return servers.get(serverId, principal.getName()).global() ? "/admin/mcp-servers" : "/mcp-servers";
    } catch (IllegalArgumentException unknown) {
      return "/mcp-servers";
    }
  }
}
