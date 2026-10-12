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
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.ReasoningEffort;
import tech.wenisch.petri.agent.model.SaveModelProviderCommand;
import tech.wenisch.petri.agent.model.TextVerbosity;

/** Administration of the model providers every user can choose from. */
@Controller
public class ModelSettingsController {
  private final ModelCredentialService credentials;

  public ModelSettingsController(ModelCredentialService credentials) {
    this.credentials = credentials;
  }

  @GetMapping("/model-settings")
  String settings(Principal principal, Model model) {
    model.addAttribute("instances", credentials.globalInstances(principal.getName()));
    model.addAttribute("providerKinds", ModelProvider.values());
    model.addAttribute("reasoningEfforts", ReasoningEffort.values());
    model.addAttribute("textVerbosities", TextVerbosity.values());
    model.addAttribute("allowUserProviders", credentials.allowUserProviders());
    return "model-settings";
  }

  @PostMapping("/model-settings/providers")
  String save(
      @RequestParam(required = false) UUID id,
      @RequestParam(required = false) ModelProvider provider,
      @RequestParam String displayName,
      @RequestParam(required = false) String baseUrl,
      @RequestParam String model,
      @RequestParam(required = false) String apiKey,
      @RequestParam(defaultValue = "false") boolean clearApiKey,
      @RequestParam(required = false) Integer maxOutputTokens,
      @RequestParam(required = false) Integer contextWindowTokens,
      @RequestParam(required = false) ReasoningEffort reasoningEffort,
      @RequestParam(required = false) TextVerbosity textVerbosity,
      @RequestParam(defaultValue = "false") boolean enabled,
      Principal principal,
      RedirectAttributes redirect) {
    try {
      var saved = credentials.saveGlobal(id, new SaveModelProviderCommand(
          provider, displayName, baseUrl, model, apiKey, clearApiKey, maxOutputTokens,
          contextWindowTokens, reasoningEffort, textVerbosity, enabled), principal.getName());
      redirect.addFlashAttribute("notice", "Saved " + saved.displayName());
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/model-settings";
  }

  @PostMapping("/model-settings/providers/{id}/delete")
  String delete(@PathVariable UUID id, Principal principal, RedirectAttributes redirect) {
    try {
      credentials.delete(id, principal.getName());
      redirect.addFlashAttribute("notice", "Provider removed");
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/model-settings";
  }

  @PostMapping("/model-settings/policy")
  String policy(
      @RequestParam(defaultValue = "false") boolean allowUserProviders,
      Principal principal,
      RedirectAttributes redirect) {
    credentials.setAllowUserProviders(allowUserProviders, principal.getName());
    redirect.addFlashAttribute("notice", allowUserProviders
        ? "Users can now add their own model providers"
        : "Users can no longer use personal model providers");
    return "redirect:/model-settings";
  }
}
