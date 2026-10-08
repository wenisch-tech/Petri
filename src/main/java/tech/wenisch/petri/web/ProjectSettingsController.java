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
import tech.wenisch.petri.git.GitAuthenticationType;
import tech.wenisch.petri.git.GitOperationException;
import tech.wenisch.petri.git.ProjectGitCredentialService;
import tech.wenisch.petri.git.ProjectMirrorService;
import tech.wenisch.petri.git.SaveGitCredentialCommand;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;

@Controller
public class ProjectSettingsController {
  private final ProjectRepository projects;
  private final ProjectGitCredentialService credentials;
  private final ProjectMirrorService mirrors;

  public ProjectSettingsController(
      ProjectRepository projects,
      ProjectGitCredentialService credentials,
      ProjectMirrorService mirrors) {
    this.projects = projects;
    this.credentials = credentials;
    this.mirrors = mirrors;
  }

  @GetMapping("/projects/{projectId}/settings")
  String settings(@PathVariable UUID projectId, Model model) {
    if (!model.containsAttribute("gitCredentialForm")) {
      model.addAttribute("gitCredentialForm", new GitCredentialForm());
    }
    populate(model, projectId);
    return "project-settings";
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
      populate(model, projectId);
      return "project-settings";
    }
    try {
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
      populate(model, projectId);
      return "project-settings";
    }
  }

  @PostMapping("/projects/{projectId}/credential/delete")
  String removeCredential(
      @PathVariable UUID projectId,
      Principal principal,
      RedirectAttributes redirectAttributes) {
    try {
      credentials.remove(projectId, principal.getName());
      redirectAttributes.addFlashAttribute("credentialRemoved", true);
      return "redirect:/projects/" + projectId + "/settings";
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
    }
  }

  @PostMapping("/projects/{projectId}/mirror/sync")
  String synchronizeMirror(
      @PathVariable UUID projectId,
      Principal principal,
      RedirectAttributes redirectAttributes) {
    try {
      mirrors.synchronize(projectId, principal);
      redirectAttributes.addFlashAttribute("mirrorSynchronized", true);
    } catch (GitOperationException exception) {
      redirectAttributes.addFlashAttribute("mirrorError", exception.getMessage());
    }
    return "redirect:/projects/" + projectId + "/settings";
  }

  private void populate(Model model, UUID projectId) {
    Project project = projects.findById(projectId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    model.addAttribute("project", project);
    model.addAttribute("credential", credentials.status(projectId));
    model.addAttribute("authenticationTypes", GitAuthenticationType.values());
    model.addAttribute("mirror", mirrors.status(projectId));
  }
}
