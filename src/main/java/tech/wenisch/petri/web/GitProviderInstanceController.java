package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;
import tech.wenisch.petri.git.provider.GitProviderInstanceService;
import tech.wenisch.petri.git.provider.GitProviderType;

@Controller
public class GitProviderInstanceController {
  private final GitProviderInstanceService instances;

  public GitProviderInstanceController(GitProviderInstanceService instances) {
    this.instances = instances;
  }

  @GetMapping("/git-provider-instances")
  String instances(Principal principal, Model model) {
    model.addAttribute("instances", instances.manageable(principal.getName()));
    model.addAttribute("providerTypes", GitProviderType.values());
    return "git-provider-instances";
  }

  @PostMapping("/git-provider-instances")
  String save(
      @RequestParam(required = false) UUID id,
      @RequestParam GitProviderType providerType,
      @RequestParam String displayName,
      @RequestParam String webBaseUrl,
      @RequestParam String apiBaseUrl,
      @RequestParam(required = false) String oauthAuthorizationUrl,
      @RequestParam(required = false) String oauthTokenUrl,
      @RequestParam(required = false) String oauthScopes,
      @RequestParam(required = false) String oauthClientId,
      @RequestParam(required = false) String oauthClientSecret,
      @RequestParam(defaultValue = "false") boolean enabled,
      Principal principal,
      RedirectAttributes redirect) {
    try {
      instances.save(new GitProviderInstanceService.SaveGitProviderInstanceCommand(
          id, providerType, displayName, webBaseUrl, apiBaseUrl,
          oauthAuthorizationUrl, oauthTokenUrl, oauthScopes,
          oauthClientId, oauthClientSecret, enabled), principal.getName());
      redirect.addFlashAttribute("notice", "Provider saved");
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/git-provider-instances";
  }
}
