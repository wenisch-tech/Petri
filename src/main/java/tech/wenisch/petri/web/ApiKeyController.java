package tech.wenisch.petri.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.apikey.ApiKeyService;
import tech.wenisch.petri.apikey.CreateApiKeyCommand;
import tech.wenisch.petri.apikey.IssuedApiKey;

@Controller
public class ApiKeyController {
  private final ApiKeyService apiKeys;

  public ApiKeyController(ApiKeyService apiKeys) {
    this.apiKeys = apiKeys;
  }

  @GetMapping("/api-keys")
  String apiKeys(Model model, Principal principal) {
    model.addAttribute("apiKeys", apiKeys.findAll(principal.getName()));
    return "api-keys";
  }

  @GetMapping("/api-keys/new")
  String newApiKey(Model model) {
    if (!model.containsAttribute("apiKeyForm")) model.addAttribute("apiKeyForm", new ApiKeyForm());
    return "api-key-new";
  }

  @PostMapping("/api-keys")
  String createApiKey(
      @Valid @ModelAttribute ApiKeyForm apiKeyForm,
      BindingResult bindingResult,
      Principal principal,
      Model model,
      HttpServletResponse response) {
    if (bindingResult.hasErrors()) return "api-key-new";
    try {
      IssuedApiKey issued = apiKeys.create(
          new CreateApiKeyCommand(apiKeyForm.getName(), apiKeyForm.getExpiresOn()),
          principal.getName());
      response.setHeader("Cache-Control", "no-store, max-age=0");
      response.setHeader("Pragma", "no-cache");
      model.addAttribute("issuedKey", issued);
      return "api-key-created";
    } catch (IllegalArgumentException exception) {
      model.addAttribute("formError", exception.getMessage());
      return "api-key-new";
    }
  }

  @PostMapping("/api-keys/{keyId}/revoke")
  String revokeApiKey(
      @PathVariable UUID keyId, Principal principal, RedirectAttributes redirectAttributes) {
    boolean revoked = apiKeys.revoke(keyId, principal.getName());
    redirectAttributes.addFlashAttribute(
        "statusMessage", revoked ? "API key revoked." : "API key was already revoked.");
    return "redirect:/api-keys";
  }
}
