package tech.wenisch.petri.web;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.agent.run.TestSandboxProfileService;
import tech.wenisch.petri.git.GitAuthenticationType;
import tech.wenisch.petri.git.GitOperationException;
import tech.wenisch.petri.git.ProjectGitCredentialService;
import tech.wenisch.petri.git.ProjectMirrorService;
import tech.wenisch.petri.git.SaveGitCredentialCommand;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectAccessService;
import tech.wenisch.petri.git.provider.ProjectProviderSourceService;

@Controller
public class ProjectSettingsController {
  private final ProjectGitCredentialService credentials;
  private final ProjectMirrorService mirrors;
  private final ProjectAccessService access;
  private final ProjectProviderSourceService providerSources;
  private final TestSandboxProfileService sandboxProfiles;

  public ProjectSettingsController(
      ProjectGitCredentialService credentials,
      ProjectMirrorService mirrors,
      ProjectAccessService access,
      ProjectProviderSourceService providerSources,
      TestSandboxProfileService sandboxProfiles) {
    this.credentials = credentials;
    this.mirrors = mirrors;
    this.access = access;
    this.providerSources = providerSources;
    this.sandboxProfiles = sandboxProfiles;
  }

  @GetMapping("/projects/{projectId}/settings")
  String settings(
      @PathVariable UUID projectId,
      @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean embedded,
      Principal principal,
      Model model) {
    access.requireProject(projectId, principal.getName());
    if (!model.containsAttribute("gitCredentialForm")) {
      model.addAttribute("gitCredentialForm", new GitCredentialForm());
    }
    populate(model, projectId, principal.getName());
    // The workspace shows this page in place; only the content is rendered then.
    return embedded ? "project-settings :: content" : "project-settings";
  }

  @PostMapping("/projects/{projectId}/credential")
  String saveCredential(
      @PathVariable UUID projectId,
      @Valid @ModelAttribute GitCredentialForm gitCredentialForm,
      BindingResult bindingResult,
      Principal principal,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      access.requireProject(projectId, principal.getName());
      populate(model, projectId, principal.getName());
      return "project-settings";
    }
    try {
      access.requireProject(projectId, principal.getName());
      credentials.save(
          projectId,
          new SaveGitCredentialCommand(
              gitCredentialForm.getAuthenticationType(),
              gitCredentialForm.getUsername(),
              gitCredentialForm.getSecret()),
          principal.getName());
      redirectAttributes.addFlashAttribute("credentialSaved", true);
      return "redirect:/projects/" + projectId + "/settings";
    } catch (IllegalArgumentException | IllegalStateException exception) {
      model.addAttribute("formError", exception.getMessage());
      populate(model, projectId, principal.getName());
      return "project-settings";
    }
  }

  @PostMapping("/projects/{projectId}/credential/delete")
  String removeCredential(
      @PathVariable UUID projectId,
      Principal principal,
      RedirectAttributes redirectAttributes) {
    try {
      access.requireProject(projectId, principal.getName());
      credentials.remove(projectId, principal.getName());
      redirectAttributes.addFlashAttribute("credentialRemoved", true);
      return "redirect:/projects/" + projectId + "/settings";
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
    }
  }

  @PostMapping("/projects/{projectId}/sandbox")
  String saveSandbox(
      @PathVariable UUID projectId,
      @org.springframework.web.bind.annotation.RequestParam UUID profileId,
      @org.springframework.web.bind.annotation.RequestParam(defaultValue = "PROFILE") String networkMode,
      @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean autoApprovePackageInstalls,
      @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean autoApproveTerminalCommands,
      Principal principal,
      RedirectAttributes redirectAttributes) {
    access.requireProject(projectId, principal.getName());
    Boolean network = switch (networkMode) {
      case "ENABLED" -> true;
      case "DISABLED" -> false;
      default -> null;
    };
    try {
      sandboxProfiles.configureProject(projectId, profileId, network, autoApprovePackageInstalls,
          autoApproveTerminalCommands, principal.getName());
      redirectAttributes.addFlashAttribute("sandboxSaved", true);
    } catch (IllegalArgumentException exception) {
      redirectAttributes.addFlashAttribute("mirrorError", exception.getMessage());
    }
    return "redirect:/projects/" + projectId + "/settings";
  }

  @PostMapping("/projects/{projectId}/mirror/sync")
  String synchronizeMirror(
      @PathVariable UUID projectId,
      Principal principal,
      RedirectAttributes redirectAttributes) {
    try {
      access.requireProject(projectId, principal.getName());
      mirrors.synchronize(projectId, principal);
      redirectAttributes.addFlashAttribute("mirrorSynchronized", true);
    } catch (GitOperationException exception) {
      redirectAttributes.addFlashAttribute("mirrorError", exception.getMessage());
    }
    return "redirect:/projects/" + projectId + "/settings";
  }

  @PostMapping("/projects/{projectId}/credential/use-simple")
  String useSimpleCredentials(
      @PathVariable UUID projectId, Principal principal, RedirectAttributes redirectAttributes) {
    providerSources.useSimpleGitCredentials(projectId, principal.getName());
    redirectAttributes.addFlashAttribute("credentialSaved", true);
    return "redirect:/projects/" + projectId + "/settings";
  }

  private void populate(Model model, UUID projectId, String actorEmail) {
    Project project = access.requireProject(projectId, actorEmail);
    model.addAttribute("project", project);
    model.addAttribute("credential", credentials.status(projectId));
    model.addAttribute("authenticationTypes", GitAuthenticationType.values());
    model.addAttribute("mirror", mirrors.status(projectId));
    model.addAttribute("providerSource", providerSources.find(projectId));
    model.addAttribute("sandboxProfiles", sandboxProfiles.all().stream()
        .filter(value -> value.isEnabled()).toList());
    model.addAttribute("sandboxChoice", sandboxProfiles.projectChoice(projectId));
  }
}
