package tech.wenisch.petri.web;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Arrays;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.ReasoningEffort;
import tech.wenisch.petri.agent.model.SaveModelCredentialCommand;
import tech.wenisch.petri.agent.model.TextVerbosity;
import tech.wenisch.petri.config.PetriProperties;

@Controller
public class ModelSettingsController {
  private final ModelCredentialService credentials;
  private final PetriProperties properties;

  public ModelSettingsController(
      ModelCredentialService credentials, PetriProperties properties) {
    this.credentials = credentials;
    this.properties = properties;
  }

  @GetMapping("/model-settings")
  String settings(Model model) {
    if (!model.containsAttribute("modelSettingsForm")) {
      model.addAttribute("modelSettingsForm", new ModelSettingsForm());
    }
    populate(model);
    return "model-settings";
  }

  @PostMapping("/model-settings")
  String saveOpenAi(
      @Valid @ModelAttribute ModelSettingsForm modelSettingsForm,
      BindingResult bindingResult,
      Principal principal,
      Model model,
      RedirectAttributes redirect) {
    return save(ModelProvider.OPENAI, modelSettingsForm, bindingResult, principal, model, redirect);
  }

  @PostMapping("/model-settings/{provider}")
  String saveProvider(
      @PathVariable ModelProvider provider,
      @Valid @ModelAttribute ModelSettingsForm modelSettingsForm,
      BindingResult bindingResult,
      Principal principal,
      Model model,
      RedirectAttributes redirect) {
    return save(provider, modelSettingsForm, bindingResult, principal, model, redirect);
  }

  private String save(
      ModelProvider provider,
      ModelSettingsForm modelSettingsForm,
      BindingResult bindingResult,
      Principal principal,
      Model model,
      RedirectAttributes redirect) {
    if (bindingResult.hasErrors()) {
      modelSettingsForm.setApiKey(null);
      model.addAttribute("submittedProvider", provider);
      populate(model);
      return "model-settings";
    }
    try {
      credentials.save(
          provider,
          new SaveModelCredentialCommand(
              modelSettingsForm.getApiKey(),
              modelSettingsForm.getModel(),
              modelSettingsForm.getMaxOutputTokens(),
              modelSettingsForm.getReasoningEffort(),
              modelSettingsForm.getTextVerbosity(),
              modelSettingsForm.getBaseUrl()),
          principal.getName());
      redirect.addFlashAttribute("settingsSaved", true);
      return "redirect:/model-settings";
    } catch (IllegalArgumentException | IllegalStateException exception) {
      modelSettingsForm.setApiKey(null);
      model.addAttribute("formError", exception.getMessage());
      model.addAttribute("submittedProvider", provider);
      populate(model);
      return "model-settings";
    }
  }

  @PostMapping("/model-settings/delete")
  String remove(Principal principal, RedirectAttributes redirect) {
    return remove(ModelProvider.OPENAI, principal, redirect);
  }

  @PostMapping("/model-settings/{provider}/delete")
  String removeProvider(
      @PathVariable ModelProvider provider,
      Principal principal,
      RedirectAttributes redirect) {
    return remove(provider, principal, redirect);
  }

  private String remove(
      ModelProvider provider, Principal principal, RedirectAttributes redirect) {
    credentials.remove(provider, principal.getName());
    redirect.addFlashAttribute("settingsRemoved", true);
    return "redirect:/model-settings";
  }

  private void populate(Model model) {
    model.addAttribute("credentials", Arrays.stream(ModelProvider.values())
        .map(credentials::status)
        .toList());
    model.addAttribute("reasoningEfforts", ReasoningEffort.values());
    model.addAttribute("textVerbosities", TextVerbosity.values());
    model.addAttribute("defaultMaxOutputTokens", properties.agent().maxOutputTokens());
  }
}
