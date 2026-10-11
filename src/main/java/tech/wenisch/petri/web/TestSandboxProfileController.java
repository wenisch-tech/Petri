package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.agent.run.TestBuildSystem;
import tech.wenisch.petri.agent.run.TestSandboxProfileService;
import tech.wenisch.petri.project.ProjectRepository;

@Controller
public class TestSandboxProfileController {
  private final TestSandboxProfileService profiles;
  private final ProjectRepository projects;

  public TestSandboxProfileController(
      TestSandboxProfileService profiles, ProjectRepository projects) {
    this.profiles = profiles;
    this.projects = projects;
  }

  @GetMapping("/sandbox-profiles")
  String profiles(Model model) {
    model.addAttribute("profiles", profiles.all());
    model.addAttribute("buildSystems", TestBuildSystem.values());
    model.addAttribute("projects", projects.findAllByOrderByCreatedAtDesc());
    return "sandbox-profiles";
  }

  @PostMapping("/sandbox-profiles")
  String save(
      @RequestParam(required = false) UUID id,
      @RequestParam String name,
      @RequestParam String image,
      @RequestParam List<TestBuildSystem> buildSystems,
      @RequestParam String cpuLimit,
      @RequestParam String memoryLimit,
      @RequestParam int pidsLimit,
      @RequestParam int timeoutSeconds,
      @RequestParam int outputLimitBytes,
      @RequestParam(defaultValue = "false") boolean networkEnabled,
      @RequestParam(defaultValue = "false") boolean enabled,
      Principal principal,
      RedirectAttributes redirect) {
    try {
      profiles.save(id, name, image, Set.copyOf(buildSystems), cpuLimit, memoryLimit,
          pidsLimit, timeoutSeconds, outputLimitBytes, networkEnabled, enabled, principal.getName());
      redirect.addFlashAttribute("notice", "Sandbox profile saved");
    } catch (RuntimeException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/sandbox-profiles";
  }

  @PostMapping("/sandbox-profiles/project")
  String configureProject(
      @RequestParam UUID projectId,
      @RequestParam UUID profileId,
      @RequestParam String networkMode,
      @RequestParam(defaultValue = "false") boolean autoApprovePackageInstalls,
      Principal principal,
      RedirectAttributes redirect) {
    Boolean network = switch (networkMode) {
      case "ENABLED" -> true;
      case "DISABLED" -> false;
      default -> null;
    };
    profiles.configureProject(projectId, profileId, network, autoApprovePackageInstalls, principal.getName());
    redirect.addFlashAttribute("notice", "Project sandbox configured");
    return "redirect:/sandbox-profiles";
  }
}
