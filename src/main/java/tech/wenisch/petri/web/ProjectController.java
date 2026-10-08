package tech.wenisch.petri.web;

import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.project.CreateProjectCommand;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.project.ProjectService;

@Controller
public class ProjectController {
  private final ProjectRepository projects;
  private final ProjectService projectService;

  public ProjectController(ProjectRepository projects, ProjectService projectService) {
    this.projects = projects;
    this.projectService = projectService;
  }

  @GetMapping("/projects")
  String projects(Model model, Authentication authentication) {
    model.addAttribute("projects", projects.findAllByOrderByCreatedAtDesc());
    model.addAttribute("canManageProjects", authentication.getAuthorities().stream()
        .anyMatch(authority -> "ROLE_INSTANCE_ADMIN".equals(authority.getAuthority())));
    return "projects";
  }

  @GetMapping("/projects/new")
  String newProject(Model model) {
    if (!model.containsAttribute("projectForm")) {
      model.addAttribute("projectForm", new ProjectForm());
    }
    return "project-new";
  }

  @PostMapping("/projects")
  String createProject(
      @Valid @ModelAttribute ProjectForm projectForm,
      BindingResult bindingResult,
      Principal principal,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) return "project-new";
    try {
      Project project = projectService.create(
          new CreateProjectCommand(
              projectForm.getName(),
              projectForm.getRepositoryUri(),
              projectForm.getDefaultBranch()),
          principal.getName());
      redirectAttributes.addFlashAttribute("createdProject", project.getName());
      return "redirect:/projects";
    } catch (IllegalArgumentException exception) {
      model.addAttribute("formError", exception.getMessage());
      return "project-new";
    }
  }
}
