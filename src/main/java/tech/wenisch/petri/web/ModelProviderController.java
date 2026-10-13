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
import tech.wenisch.petri.agent.model.ModelCatalogService;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.agent.model.ModelCredentialStatus;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.ReasoningEffort;
import tech.wenisch.petri.agent.model.SaveModelProviderCommand;
import tech.wenisch.petri.agent.model.TextVerbosity;

/** A user's view of model providers: add keys to the shared ones, manage their own. */
@Controller
public class ModelProviderController {
  private final ModelCredentialService credentials;
  private final ModelCatalogService catalog;

  public ModelProviderController(ModelCredentialService credentials, ModelCatalogService catalog) {
    this.credentials = credentials;
    this.catalog = catalog;
  }

  @GetMapping("/model-providers")
  String providers(Principal principal, Model model) {
    boolean allowed = credentials.allowUserProviders();
    var shared = credentials.catalog(principal.getName()).stream()
        .filter(ModelCredentialStatus::global).toList();
    var own = credentials.ownInstances(principal.getName());
    model.addAttribute("shared", shared);
    model.addAttribute("own", own);
    model.addAttribute("catalogs", catalog.views(principal.getName(), java.util.stream.Stream
        .concat(shared.stream(), own.stream()).map(ModelCredentialStatus::id).toList()));
    model.addAttribute("allowUserProviders", allowed);
    model.addAttribute("providerKinds", ModelProvider.values());
    model.addAttribute("reasoningEfforts", ReasoningEffort.values());
    model.addAttribute("textVerbosities", TextVerbosity.values());
    return "model-providers";
  }

  @PostMapping("/model-providers/{id}/key")
  String saveKey(
      @PathVariable UUID id,
      @RequestParam String apiKey,
      Principal principal,
      RedirectAttributes redirect) {
    try {
      credentials.saveUserKey(id, apiKey, principal.getName());
      redirect.addFlashAttribute("notice", "API key saved");
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/model-providers";
  }

  @PostMapping("/model-providers/{id}/key/delete")
  String removeKey(@PathVariable UUID id, Principal principal, RedirectAttributes redirect) {
    credentials.removeUserKey(id, principal.getName());
    redirect.addFlashAttribute("notice", "API key removed");
    return "redirect:/model-providers";
  }

  @PostMapping("/model-providers/{id}/models/refresh")
  String refreshModels(@PathVariable UUID id, Principal principal, RedirectAttributes redirect) {
    try {
      int count = catalog.refresh(id, principal.getName());
      redirect.addFlashAttribute("notice", "Found " + count + " models");
    } catch (IllegalArgumentException | IllegalStateException
        | tech.wenisch.petri.agent.model.ModelClientException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/model-providers";
  }

  @PostMapping("/model-providers/{id}/models")
  String addModel(
      @PathVariable UUID id, @RequestParam String modelId, Principal principal,
      RedirectAttributes redirect) {
    try {
      catalog.addCustom(id, modelId, false, principal.getName());
      redirect.addFlashAttribute("notice", "Model added");
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/model-providers";
  }

  @PostMapping("/model-providers/models/{modelRowId}/delete")
  String removeModel(
      @PathVariable UUID modelRowId, Principal principal, RedirectAttributes redirect) {
    try {
      catalog.removeCustom(modelRowId, principal.getName());
      redirect.addFlashAttribute("notice", "Model removed");
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/model-providers";
  }

  @PostMapping("/model-providers/own")
  String saveOwn(
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
      Principal principal,
      RedirectAttributes redirect) {
    try {
      var saved = credentials.savePersonal(id, new SaveModelProviderCommand(
          provider, displayName, baseUrl, model, apiKey, clearApiKey, maxOutputTokens,
          contextWindowTokens, reasoningEffort, textVerbosity, true), principal.getName());
      redirect.addFlashAttribute("notice", "Saved " + saved.displayName());
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/model-providers";
  }

  @PostMapping("/model-providers/own/{id}/delete")
  String deleteOwn(@PathVariable UUID id, Principal principal, RedirectAttributes redirect) {
    try {
      credentials.delete(id, principal.getName());
      redirect.addFlashAttribute("notice", "Provider removed");
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/model-providers";
  }
}
