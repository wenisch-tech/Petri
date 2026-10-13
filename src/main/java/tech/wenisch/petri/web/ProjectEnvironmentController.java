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
import tech.wenisch.petri.agent.run.TestSandboxProfileService;
import tech.wenisch.petri.project.ProjectAccessService;

/** Environment variables a project adds to its terminal commands, tests and package installs. */
@Controller
public class ProjectEnvironmentController {
  private final ProjectAccessService access;
  private final TestSandboxProfileService sandboxProfiles;

  public ProjectEnvironmentController(
      ProjectAccessService access, TestSandboxProfileService sandboxProfiles) {
    this.access = access;
    this.sandboxProfiles = sandboxProfiles;
  }

  @GetMapping("/projects/{projectId}/settings/environment")
  String environment(
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "false") boolean embedded,
      Principal principal,
      Model model) {
    model.addAttribute("project", access.requireProject(projectId, principal.getName()));
    if (!model.containsAttribute("environmentText")) {
      model.addAttribute("environmentText", sandboxProfiles.maskedProjectEnvironment(projectId));
    }
    model.addAttribute("inherited", sandboxProfiles.inheritedVariableNames(projectId));
    return embedded ? "project-environment :: content" : "project-environment";
  }

  @PostMapping("/projects/{projectId}/settings/environment")
  String save(
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "") String environment,
      Principal principal,
      RedirectAttributes redirect) {
    access.requireProject(projectId, principal.getName());
    try {
      sandboxProfiles.saveProjectEnvironment(projectId, environment, principal.getName());
      redirect.addFlashAttribute("environmentSaved", true);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("environmentError", exception.getMessage());
      redirect.addFlashAttribute("environmentText", environment);
    }
    return "redirect:/projects/" + projectId + "/settings/environment";
  }
}
