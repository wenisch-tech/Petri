package tech.wenisch.petri.web;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
import tech.wenisch.petri.project.ProjectAccessService;
import tech.wenisch.petri.project.ProjectInitializationJob;
import tech.wenisch.petri.project.ProjectInitializationJobRepository;
import tech.wenisch.petri.project.ProjectInitializationLauncher;
import tech.wenisch.petri.project.ProjectService;
import tech.wenisch.petri.git.provider.GitAccountConnectionService;
import tech.wenisch.petri.git.provider.ProjectProviderSource;
import tech.wenisch.petri.git.provider.ProjectProviderSourceRepository;

@Controller
public class ProjectController {
  private final ProjectRepository projects;
  private final ProjectService projectService;
  private final ProjectAccessService access;
  private final GitAccountConnectionService connections;
  private final ProjectProviderSourceRepository sources;
  private final ProjectInitializationJobRepository jobs;
  private final ProjectInitializationLauncher initialization;

  public ProjectController(
      ProjectRepository projects,
      ProjectService projectService,
      ProjectAccessService access,
      GitAccountConnectionService connections,
      ProjectProviderSourceRepository sources,
      ProjectInitializationJobRepository jobs,
      ProjectInitializationLauncher initialization) {
    this.projects = projects;
    this.projectService = projectService;
    this.access = access;
    this.connections = connections;
    this.sources = sources;
    this.jobs = jobs;
    this.initialization = initialization;
  }

  @GetMapping("/projects")
  String projects(Model model, Authentication authentication, Principal principal) {
    var ownedProjects = access.projects(principal.getName());
    var accountById = connections.list(principal.getName()).stream()
        .collect(Collectors.toMap(value -> value.id(), value -> value));
    model.addAttribute("projects", ownedProjects);
    model.addAttribute("projectSources", ownedProjects.stream()
        .map(project -> sources.findByProjectId(project.getId()).orElse(null))
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toMap(ProjectProviderSource::getProjectId, value -> value)));
    model.addAttribute("initializationJobs", ownedProjects.stream()
        .map(project -> jobs.findByProjectId(project.getId()).orElse(null))
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toMap(ProjectInitializationJob::getProjectId, value -> value)));
    model.addAttribute("projectConnections", ownedProjects.stream()
        .map(project -> sources.findByProjectId(project.getId()).orElse(null))
        .filter(java.util.Objects::nonNull)
        .filter(source -> accountById.containsKey(source.getConnectionId()))
        .collect(Collectors.toMap(
            ProjectProviderSource::getProjectId,
            source -> accountById.get(source.getConnectionId()))));
    model.addAttribute("canManageProjects", true);
    model.addAttribute("isInstanceAdmin", authentication.getAuthorities().stream()
        .anyMatch(authority -> "ROLE_INSTANCE_ADMIN".equals(authority.getAuthority())));
    return "projects";
  }

  @GetMapping("/projects/new")
  String newProject(Model model, Principal principal) {
    if (!model.containsAttribute("projectForm")) {
      model.addAttribute("projectForm", new ProjectForm());
    }
    model.addAttribute("connections", connections.list(principal.getName()).stream()
        .filter(value -> value.status() == tech.wenisch.petri.git.provider.GitConnectionStatus.ACTIVE)
        .toList());
    return "project-new";
  }

  @PostMapping("/projects")
  String createProject(
      @Valid @ModelAttribute ProjectForm projectForm,
      BindingResult bindingResult,
      Principal principal,
      Model model,
      RedirectAttributes redirectAttributes) {
    if (bindingResult.hasErrors()) {
      model.addAttribute("connections", connections.list(principal.getName()));
      return "project-new";
    }
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
      model.addAttribute("connections", connections.list(principal.getName()));
      return "project-new";
    }
  }

  @PostMapping("/projects/{projectId}/initialization/retry")
  String retryInitialization(
      @org.springframework.web.bind.annotation.PathVariable UUID projectId,
      Principal principal,
      RedirectAttributes redirectAttributes) {
    access.requireProject(projectId, principal.getName());
    initialization.retry(projectId);
    redirectAttributes.addFlashAttribute("createdProject", "Initialization restarted");
    return "redirect:/projects";
  }
}
